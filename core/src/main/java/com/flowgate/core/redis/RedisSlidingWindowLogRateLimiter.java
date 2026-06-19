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
 * Redis-backed sliding window log rate limiter.
 *
 * <h3>Why a sorted set (ZSET), not a hash?</h3>
 * The in-memory implementation stores every request timestamp in an {@code ArrayDeque<Long>}.
 * Redis doesn't have a deque, but it has a sorted set — a data structure where each member
 * has a numeric score. By storing request timestamps as scores, {@code ZREMRANGEBYSCORE}
 * evicts every entry outside the window in one atomic operation. {@code ZCARD} counts what's
 * left. The structure maps directly:
 * <pre>
 *   ArrayDeque.addLast(now)         →  ZADD key now "timestamp:n"
 *   while peekFirst() <= cutoff     →  ZREMRANGEBYSCORE key -inf cutoff
 *   window.size()                   →  ZCARD key
 *   window.peekFirst()              →  ZRANGE key 0 0 WITHSCORES
 * </pre>
 *
 * <h3>Member uniqueness</h3>
 * Sorted set members must be unique. Two requests arriving in the same millisecond
 * would produce the same timestamp, causing a collision. We suffix each member with
 * its position in the set ({@code "timestamp:count+i"}). Since Lua scripts execute
 * atomically in Redis, the count is stable for the duration of the script, making
 * every member value distinct.
 *
 * <h3>Trade-off vs. sliding window counter</h3>
 * This algorithm is exact: every individual timestamp is recorded. The counter variant
 * stores only three numbers but uses a weighted approximation. Which to use depends on
 * whether you need precision or constant memory per key.
 *
 * <h3>TTL</h3>
 * The key expires after one window of inactivity. Once all entries have aged out,
 * the sorted set is empty and there's nothing useful to preserve.
 */
public class RedisSlidingWindowLogRateLimiter implements RateLimiter {

    /**
     * KEYS[1]  — Redis key for this window (sorted set)
     * ARGV[1]  — limit      (max requests in the window)
     * ARGV[2]  — requested  (tokens this request wants to consume)
     * ARGV[3]  — now        (current time in milliseconds since epoch)
     * ARGV[4]  — windowMs   (window length in milliseconds)
     *
     * Returns {allowed (1/0), remaining, retryMs (0 if allowed)}
     *
     * Steps:
     *  1. ZREMRANGEBYSCORE — evict timestamps older than (now - windowMs).
     *  2. ZCARD — count remaining timestamps inside the window.
     *  3. If count + requested > limit: reject with precise retry time.
     *  4. Otherwise: ZADD `requested` entries, set TTL, return allowed.
     */
    private static final String LUA_SCRIPT = """
            local key       = KEYS[1]
            local limit     = tonumber(ARGV[1])
            local requested = tonumber(ARGV[2])
            local now       = tonumber(ARGV[3])
            local window_ms = tonumber(ARGV[4])

            -- Evict entries whose score (timestamp) has fallen outside the window.
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window_ms)

            -- Count how many timestamps remain inside the window.
            local count = tonumber(redis.call('ZCARD', key))

            -- Would admitting `requested` entries exceed the limit?
            if count + requested > limit then
                -- Precise retry: time until the oldest entry ages out of the window.
                local oldest = redis.call('ZRANGE', key, '0', '0', 'WITHSCORES')
                local retry_ms = 0
                if #oldest > 0 then
                    retry_ms = math.max(0, math.ceil(tonumber(oldest[2]) + window_ms - now))
                end
                return {0, math.max(0, limit - count), retry_ms}
            end

            -- Admit: store `requested` timestamped entries.
            -- Member = "timestamp:position" ensures uniqueness within the sorted set
            -- even when multiple requests arrive within the same millisecond.
            for i = 1, requested do
                redis.call('ZADD', key, now, tostring(now) .. ':' .. tostring(count + i))
            end

            -- Expire after one full window of inactivity.
            redis.call('PEXPIRE', key, window_ms)

            return {1, limit - (count + requested), 0}
            """;

    private final RateLimiterConfig config;
    private final RedisCommands<String, String> redis;
    private final MillisClock clock;

    public RedisSlidingWindowLogRateLimiter(RateLimiterConfig config, RedisClient redisClient) {
        this(config, redisClient, System::currentTimeMillis);
    }

    public RedisSlidingWindowLogRateLimiter(RateLimiterConfig config, RedisClient redisClient, MillisClock clock) {
        this.config = config;
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        this.redis = connection.sync();
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        String redisKey = "flowgate:sliding_window_log:" + key;
        long now = clock.millis();

        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) redis.eval(
                LUA_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{redisKey},
                String.valueOf(config.limit()),
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
