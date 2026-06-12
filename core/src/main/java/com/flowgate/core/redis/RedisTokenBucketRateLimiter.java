package com.flowgate.core.redis;

import com.flowgate.core.MillisClock;
import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Redis-backed token bucket rate limiter.
 *
 * <h3>Why Redis instead of in-memory?</h3>
 * The in-memory implementation stores bucket state in a ConcurrentHashMap inside the JVM.
 * That works correctly for a single instance, but breaks the moment you run two app instances
 * behind a load balancer — each has its own map, so a user can exceed the limit by hitting
 * different instances. Redis gives all instances one shared source of truth.
 *
 * <h3>Why a Lua script?</h3>
 * The token bucket algorithm requires read-then-write: read current tokens, calculate refill,
 * write new state. If done as two separate Redis commands (GET then SET), two concurrent
 * requests can both read stale state and both believe they have enough tokens — a classic
 * TOCTOU race condition. Redis executes Lua scripts atomically: no other command can
 * interleave between the HMGET and HMSET inside the script. This is the distributed
 * equivalent of the CAS loop used in the in-memory implementation.
 *
 * <h3>Why milliseconds, not nanoseconds?</h3>
 * Lua uses IEEE 754 doubles for all numbers. A double has 53 bits of mantissa, which means
 * it can represent integers exactly up to 2^53 ≈ 9 × 10^15. Epoch nanoseconds are currently
 * around 1.7 × 10^18 — beyond that limit, so nanosecond timestamps lose precision when
 * passed through Lua. Milliseconds (~1.7 × 10^12) are well within the safe range.
 *
 * <h3>Key structure</h3>
 * Keys are namespaced as {@code flowgate:token_bucket:<caller-supplied-key>}.
 * The caller controls the key semantics: "user:42", "ip:203.0.113.1", etc.
 *
 * <h3>TTL strategy</h3>
 * Each write sets PEXPIRE to 2× the window. This means:
 * - An inactive user's bucket is garbage-collected after 2 windows.
 * - On the next request after expiry, Redis treats the key as new and the bucket starts full —
 *   which is correct behaviour (the user has been idle long enough to have a full refill).
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    /**
     * Lua script that executes atomically on the Redis server.
     *
     * KEYS[1]  — the Redis key for this bucket (e.g. "flowgate:token_bucket:user:42")
     * ARGV[1]  — burstCapacity  (max tokens the bucket can hold)
     * ARGV[2]  — refillRate     (tokens per millisecond, as a decimal)
     * ARGV[3]  — requested      (tokens this request wants to consume)
     * ARGV[4]  — now            (current time in milliseconds since epoch)
     * ARGV[5]  — windowMs       (window length in milliseconds, used for TTL)
     *
     * Returns a 3-element array:
     *   [0] — 1 if allowed, 0 if rejected
     *   [1] — remaining tokens after this request (floored to integer)
     *   [2] — milliseconds until enough tokens refill (0 if allowed)
     */
    private static final String LUA_SCRIPT = """
            local key          = KEYS[1]
            local burst        = tonumber(ARGV[1])
            local refill_rate  = tonumber(ARGV[2])
            local requested    = tonumber(ARGV[3])
            local now          = tonumber(ARGV[4])
            local window_ms    = tonumber(ARGV[5])

            -- Read current state. HMGET returns nil for a key that doesn't exist yet.
            local state       = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens      = tonumber(state[1])
            local last_refill = tonumber(state[2])

            -- First request: initialise bucket to full capacity.
            if tokens == nil then
                tokens      = burst
                last_refill = now
            end

            -- Refill based on how much time has passed since the last request.
            local elapsed = now - last_refill
            tokens = math.min(burst, tokens + elapsed * refill_rate)

            -- Not enough tokens: reject and tell the caller how long to wait.
            if tokens < requested then
                local retry_ms = math.ceil((requested - tokens) / refill_rate)
                return {0, math.floor(tokens), retry_ms}
            end

            -- Enough tokens: consume and persist the new state.
            tokens = tokens - requested
            redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_refill', tostring(now))

            -- Set TTL to 2× window. An idle bucket expires automatically after two windows.
            redis.call('PEXPIRE', key, window_ms * 2)

            return {1, math.floor(tokens), 0}
            """;

    private final RateLimiterConfig config;
    private final RedisCommands<String, String> redis;

    /**
     * Tokens that refill per millisecond.
     * Computed once at construction: config.limit() / config.window().toMillis().
     * Example: limit=100, window=60s → 100/60000 = 0.001666... tokens/ms.
     */
    private final double refillRatePerMs;

    /**
     * Clock used for the "now" argument passed to the Lua script.
     * Defaults to real time in production; tests inject a fake clock to
     * make refill behaviour deterministic without {@code Thread.sleep()}.
     */
    private final MillisClock clock;

    /**
     * Constructs a limiter backed by the given Redis client.
     *
     * <p>Uses Lettuce's synchronous API for simplicity. In a high-throughput production
     * system you would use the async API ({@code connection.async()}) to avoid blocking
     * the calling thread during network I/O. For this portfolio project, sync keeps the
     * code readable without hiding the algorithm behind reactive plumbing.
     */
    public RedisTokenBucketRateLimiter(RateLimiterConfig config, RedisClient redisClient) {
        this(config, redisClient, System::currentTimeMillis);
    }

    /**
     * Constructs a limiter with an injectable clock — used by tests to control
     * the "now" value passed into the Lua script, so refill behaviour can be
     * asserted without waiting on real time.
     */
    public RedisTokenBucketRateLimiter(RateLimiterConfig config, RedisClient redisClient, MillisClock clock) {
        this.config = config;
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        this.redis = connection.sync();
        this.refillRatePerMs = (double) config.limit() / config.window().toMillis();
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        String redisKey = "flowgate:token_bucket:" + key;
        long now = clock.millis();

        // EVAL sends the script + arguments to Redis in one round trip.
        // ScriptOutputType.MULTI tells Lettuce to deserialise the Lua table as List<Object>.
        // Each element in the returned list is a Long (Lua integers map to Java Long).
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) redis.eval(
                LUA_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{redisKey},
                String.valueOf(config.burstCapacity()),
                String.valueOf(refillRatePerMs),
                String.valueOf(tokens),
                String.valueOf(now),
                String.valueOf(config.window().toMillis())
        );

        long allowed   = (Long) result.get(0);
        long remaining = (Long) result.get(1);
        long retryMs   = (Long) result.get(2);

        if (allowed == 1) {
            return RateLimitResult.allowed(
                    config.limit(),
                    remaining,
                    Instant.now().plus(config.window())
            );
        }

        // retryMs is the precise time until the bucket has enough tokens —
        // more accurate than the in-memory version which returns the whole window.
        return RateLimitResult.rejected(
                config.limit(),
                Instant.now().plus(config.window()),
                Duration.ofMillis(retryMs)
        );
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }
}