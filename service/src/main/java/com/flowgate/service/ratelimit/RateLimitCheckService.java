package com.flowgate.service.ratelimit;

import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import com.flowgate.core.redis.RedisLeakyBucketRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowCounterRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowLogRateLimiter;
import com.flowgate.core.redis.RedisTokenBucketRateLimiter;
import io.lettuce.core.RedisClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for "is this key allowed to proceed" — shared by
 * {@link com.flowgate.service.grpc.GrpcRateLimitService} and the REST fallback
 * controller. Both transports decode their own wire format into a
 * {@link RateLimitCheckRequest} and delegate here, so the limiter cache,
 * algorithm dispatch, and validation rules exist in exactly one place.
 *
 * <h3>Limiter caching</h3>
 * Mirrors {@code RateLimitAspect}'s pattern: a {@link RateLimiter} is cheap to
 * reuse (all mutable state lives in Redis) but not free to construct (opens a
 * connection), so instances are cached by {@code (algorithm, limit, windowMillis)}.
 */
@Component
public class RateLimitCheckService {

    private final RedisClient redisClient;

    private final ConcurrentHashMap<String, RateLimiter> limiterCache = new ConcurrentHashMap<>();

    public RateLimitCheckService(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    public RateLimitResult check(RateLimitCheckRequest request) {
        validate(request);
        RateLimiter limiter = getOrCreateLimiter(request);
        return limiter.tryAcquire(request.key());
    }

    private void validate(RateLimitCheckRequest request) {
        if (request.algorithm() == null) {
            throw new IllegalArgumentException("algorithm must be set");
        }
        if (request.limit() <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + request.limit());
        }
        if (request.window() == null || request.window().isZero() || request.window().isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    private RateLimiter getOrCreateLimiter(RateLimitCheckRequest request) {
        String cacheKey = request.algorithm() + ":" + request.limit() + ":" + request.window().toMillis();
        return limiterCache.computeIfAbsent(cacheKey, k -> createLimiter(request));
    }

    private RateLimiter createLimiter(RateLimitCheckRequest request) {
        int limit = request.limit();
        var window = request.window();
        var algorithm = request.algorithm();

        RateLimiterConfig config = switch (algorithm) {
            case TOKEN_BUCKET -> RateLimiterConfig.tokenBucket(limit, window);
            case LEAKY_BUCKET -> RateLimiterConfig.leakyBucket(limit, window);
            case SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER ->
                    RateLimiterConfig.slidingWindow(algorithm, limit, window);
        };

        return switch (algorithm) {
            case TOKEN_BUCKET -> new RedisTokenBucketRateLimiter(config, redisClient);
            case LEAKY_BUCKET -> new RedisLeakyBucketRateLimiter(config, redisClient);
            case SLIDING_WINDOW_LOG -> new RedisSlidingWindowLogRateLimiter(config, redisClient);
            case SLIDING_WINDOW_COUNTER -> new RedisSlidingWindowCounterRateLimiter(config, redisClient);
        };
    }
}
