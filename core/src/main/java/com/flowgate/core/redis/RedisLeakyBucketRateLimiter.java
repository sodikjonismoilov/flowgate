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
 * Redis-backed leaky bucket rate limiter.
 *
 * <h3>How this differs from the token bucket</h3>
 * Token bucket: a reservoir of tokens that <b>refills up</b> toward a cap; a request
 * consumes tokens, and is rejected when the reservoir is empty.
 * Leaky bucket: a "water level" that <b>drains down</b> toward zero at a fixed rate;
 * a request adds to the level, and is rejected when the level would overflow capacity.
 * They're mirror images of the same idea — both reduce to "track a number that moves
 * toward a bound at a fixed rate per millisecond, and gate requests on that number."
 * This is exactly the same relationship as between the two in-memory implementations.
 *
 * <h3>State stored per key</h3>
 * A Redis hash with two fields:
 * <ul>
 *   <li>{@code level}     — current queue depth (in-memory's {@code currentLevel})</li>
 *   <li>{@code last_leak} — timestamp (ms) of the last drain calculation</li>
 * </ul>
 *
 * <h3>retryAfter calculation</h3>
 * When a request would push {@code level} over {@code capacity}, the script computes
 * exactly how much the bucket needs to drain before {@code requested} would fit:
 * {@code overflow = (level + requested) - capacity}, then
 * {@code retryMs = overflow / leakRatePerMs}. Same precision improvement over the
 * in-memory version (which just returns the whole window) as the token bucket got.
 */
public class  RedisLeakyBucketRateLimiter implements RateLimiter {

    /**
     * KEYS[1]  — Redis key for this bucket
     * ARGV[1]  — capacity      (config.limit(); max level the bucket can hold)
     * ARGV[2]  — leakRate      (units drained per millisecond, as a decimal)
     * ARGV[3]  — requested     (tokens this request wants to add)
     * ARGV[4]  — now           (current time in milliseconds since epoch)
     * ARGV[5]  — windowMs      (window length in milliseconds, used for TTL)
     *
     * Returns {allowed (1/0), remaining capacity (floored), retryMs (0 if allowed)}
     */
    private static final String LUA_SCRIPT = """
            local key       = KEYS[1]
            local capacity  = tonumber(ARGV[1])
            local leak_rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local now       = tonumber(ARGV[4])
            local window_ms = tonumber(ARGV[5])

            -- Read current state. HMGET returns nil for a key that doesn't exist yet.
            local state     = redis.call('HMGET', key, 'level', 'last_leak')
            local level     = tonumber(state[1])
            local last_leak = tonumber(state[2])

            -- First request: bucket starts empty.
            if level == nil then
                level     = 0
                last_leak = now
            end

            -- Drain based on how much time has passed since the last request.
            local elapsed = now - last_leak
            local leaked  = elapsed * leak_rate
            level = math.max(0, level - leaked)

            -- Would adding `requested` overflow capacity?
            if level + requested > capacity then
                local overflow = (level + requested) - capacity
                local retry_ms = math.ceil(overflow / leak_rate)
                return {0, math.floor(capacity - level), retry_ms}
            end

            -- Fits: add to the level and persist.
            level = level + requested
            redis.call('HMSET', key, 'level', tostring(level), 'last_leak', tostring(now))

            -- Set TTL to 2x window. An idle bucket (fully drained) expires automatically.
            redis.call('PEXPIRE', key, window_ms * 2)

            return {1, math.floor(capacity - level), 0}
            """;

    private final RateLimiterConfig config;
    private final RedisCommands<String, String> redis;

    /**
     * Units drained per millisecond.
     * Computed once: config.limit() / config.window().toMillis().
     */
    private final double leakRatePerMs;

    private final MillisClock clock;

    public RedisLeakyBucketRateLimiter(RateLimiterConfig config, RedisClient redisClient) {
        this(config, redisClient, System::currentTimeMillis);
    }

    public RedisLeakyBucketRateLimiter(RateLimiterConfig config, RedisClient redisClient, MillisClock clock) {
        this.config = config;
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        this.redis = connection.sync();
        this.leakRatePerMs = (double) config.limit() / config.window().toMillis();
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        String redisKey = "flowgate:leaky_bucket:" + key;
        long now = clock.millis();

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) redis.eval(
                LUA_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{redisKey},
                String.valueOf(config.limit()),
                String.valueOf(leakRatePerMs),
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
