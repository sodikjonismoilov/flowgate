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
 * Redis-backed sliding window counter rate limiter.
 *
 * <h3>Algorithm recap</h3>
 * Instead of logging every timestamp, this algorithm tracks only three numbers
 * per key:
 * <ul>
 *     <li>{@code curr_count}   - requests seen in the current window</li>
 *     <li>{@code prev_count}   - requests seen in the previous window </li>
 *     <li>{@code window_start} - start time (ms) of the current window</li>
 * </ul>
 * the weighted estimate of recent traffic blends the two counts:
 * <pre>
 *     estimated = curr_count + floor(prev_count × (1 − elapsed_fraction))
 * </pre>
 ** where {@code elapsed_fraction} is how far through the current window we are (0.0–1.0).
 *  * As time passes within a window, the previous window's count fades away linearly.
 *  *
 *  * <h3>Trade-off vs. sliding window log</h3>
 *  * The counter uses O(1) memory per key regardless of traffic volume.
 *  * The log uses O(n) memory where n is the request count in the window.
 *  * The counter is an approximation; the log is exact. For high-traffic API gateways
 *  * the counter is usually preferred.
 *  *
 *  * <h3>Window sliding</h3>
 *  * The Lua script checks whether the current window has expired on every request,
 *  * avoiding any background job:
 *  * <ul>
 *  *   <li>0 windows passed → still in same window, just increment</li>
 *  *   <li>1 window passed → slide: prev = curr, curr = 0, advance window_start</li>
 *  *   <li>2+ windows passed → too long since last request; prev = 0, curr = 0</li>
 *  * </ul>
 *  * This mirrors exactly the logic in {@code SlidingWindowCounterRateLimiter.tryAcquire()}.
 *  */


public class RedisSlidingWindowCounterRateLimiter implements RateLimiter {

    /**
     * KEYS[1]  - Redis key for this window (hash)
     * ARGV[1]  -  limit (max requests per window)
     * ARGV[2]  - requested (tokens this request wants to consume)
     * ARGV[3]  - now (current time in milliseconds since epoch)
     * ARGV[4]  - windowMs (window length in milliseconds)
     *
     * Returns {allowed (1/0), remaining, retryms {0 if allowed}
     */

    private static final String LUA_SCRIPT = """
            local key       = KEYS[1]
            local limit     = tonumber(ARGV[1])
            local requested = tonumber(ARGV[2]) 
            local now    = tonumber(ARGV[3])
            local window_ms = tonumber(ARGV[4])
            
            local state     = redis.call('HMGET', key , 'curr_count', 'prev_count', 'window_start')
            local curr_count = tonumber(state[1]) or 0
            local prev_count = tonumber(state[2]) or 0
            local window_start = tonumber(state[3]) or now
            
            -- Slide the window if one or more full windows have elapsed. 
            if now - window_start >= window_ms then
                local windows_passed = math.floor((now - window_start) / window_ms)
                if windows_passed == 1 then 
                    prev_count = curr_count
                else 
                    prev_count = 0
                end
                curr_count = 0
                window_start = window_start + windows_passed * window_ms
            end
            
            -- Weighted blend: how much of the previous window's count still applies?
            local elapsed_fraction = math.min(1.0, (now - window_start) / window_ms)
            local weighted_prev    = math.floor(prev_count * (1.0 - elapsed_fraction))
            local estimated        = curr_count + weighted_prev
            
            if estimated + requested > limit then 
                local ms_until_reset = math.max(0, window_ms - (now - window_start))
                return {0, math.max(0, limit - estimated), ms_until_reset}
            end
            
            -- Admit: increment current window count and persist.
            curr_count = curr_count + requested
            local new_estimated  = curr_count + math.floor(prev_count * (1.0 - elapsed_fraction))
            local remaining      = math.max(0, limit - new_estimated)
            local ms_until_reset = math.max(0, window_ms - (now - window_start))

            redis.call('HMSET', key,
                    'curr_count',   tostring(curr_count),
                    'prev_count',   tostring(prev_count),
                    'window_start', tostring(window_start))

            -- TTL: 2× window keeps prev_count available after a window boundary.
            redis.call('PEXPIRE', key, window_ms * 2)

            return {1, remaining, 0}

            """;
    private final RateLimiterConfig config;
    private final RedisCommands<String, String> redis;
    private final MillisClock clock;

    public RedisSlidingWindowCounterRateLimiter(RateLimiterConfig config, RedisClient redisClient) {
        this(config, redisClient, System::currentTimeMillis);
    }

    public RedisSlidingWindowCounterRateLimiter(RateLimiterConfig config, RedisClient redisClient, MillisClock clock) {
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
        String redisKey = "flowgate:sliding_window_counter:" + key;
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
        long allowed = (Long) result.get(0);
        long remaining = (Long) result.get(1);
        long retryMs = (Long) result.get(2);

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
