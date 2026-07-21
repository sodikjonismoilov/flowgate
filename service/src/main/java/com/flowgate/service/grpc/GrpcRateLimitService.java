package com.flowgate.service.grpc;

import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import com.flowgate.core.redis.RedisLeakyBucketRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowLogRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowCounterRateLimiter;
import com.flowgate.core.redis.RedisTokenBucketRateLimiter;
import com.flowgate.service.grpc.proto.CheckRequest;
import com.flowgate.service.grpc.proto.CheckResponse;
import com.flowgate.service.grpc.proto.RateLimitServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.RedisClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC entry point for flowgate's "rate limiting as a service" API.
 *
 * <p>Unlike {@link com.flowgate.library.aspect.RateLimitAspect}, which resolves
 * its key via SpEL against annotated method arguments, callers here are polyglot
 * clients (any language with a gRPC stub) that supply the key, algorithm, limit,
 * and window explicitly on every {@link CheckRequest} - config-per-request, no server-side
 * per-key configuration store.</p>
 *
 * <h3>Limiter caching</h3>
 * Mirrors {@code RateLimitAspect}'w approach: a {@link RateLimiter} instance is
 * expensive-ish to construct (opens a Redis connection) but cheap to reuse, since
 * all mutable state lives in Redis, not in Java object. Instances are cached
 * by {@code (algorithm, limit, windowMillis)} - the same combination always maps
 * to the same limiter, regardless of which tenant key is being checked.
 */

@GrpcService
public class GrpcRateLimitService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GrpcRateLimitService.class);

    private final RedisClient redisClient;

    /** Cache: "ALGORITHM:limit:windowMillis" -> RateLimiter instance. */
    private final ConcurrentHashMap<String, RateLimiter> limiterCache = new ConcurrentHashMap<>();

    public GrpcRateLimitService(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        try {
            RateLimiter limiter = getOrCreateLimiter(request);
            RateLimitResult result = limiter.tryAcquire(request.getKey());

            log.debug("gRPC rate limit check: key={}, algorithm={}, allowed={}, remaining={}",
                    request.getKey(), request.getAlgorithm(), result.allowed(), result.remaining());


            CheckResponse response = CheckResponse.newBuilder()
                    .setAllowed(result.allowed())
                    .setRemaining(result.remaining())
                    .setRetryAfterMillis(result.retryAfter().toMillis())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            //Bad input (e.g. ALGORITHM_UNSPECIFIED, non-positive limit/window) is a
            // client error, not a server fault - surface it as INVALID_ARGUMENT rather
            //than an opaque UNKNOWN/INTERNAL status.
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException());


        }
    }
    private RateLimiter getOrCreateLimiter(CheckRequest request) {
        String cacheKey = request.getAlgorithm() + ":" + request.getLimit() + ":" + request.getWindowMillis();
        return limiterCache.computeIfAbsent(cacheKey, k -> createLimiter(request));
    }

    private RateLimiter createLimiter(CheckRequest request) {
        int limit = validatedLimit(request.getLimit());
        Duration window = validatedWindow(request.getWindowMillis());
        com.flowgate.core.Algorithm algorithm = mapAlgorithm(request.getAlgorithm());

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

    /**
     * Maps the wire-level proto enum to flowgate-core's Algorithm enum.
     * ALGORITHM_UNSPECIFIED (the proto zero-value) is rejected explicitly so a
     * caller who forgets to set the field gets clear error instead of silently
     * falling through to whichever algorithm happens to be first in the switch statement.
     *
     */

    private com.flowgate.core.Algorithm mapAlgorithm(com.flowgate.service.grpc.proto.Algorithm protoAlgorithm) {
        return switch (protoAlgorithm) {
            case TOKEN_BUCKET -> com.flowgate.core.Algorithm.TOKEN_BUCKET;
            case LEAKY_BUCKET -> com.flowgate.core.Algorithm.LEAKY_BUCKET;
            case SLIDING_WINDOW_LOG -> com.flowgate.core.Algorithm.SLIDING_WINDOW_LOG;
            case SLIDING_WINDOW_COUNTER -> com.flowgate.core.Algorithm.SLIDING_WINDOW_COUNTER;
            case ALGORITHM_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("algorithm must be set on CheckRequest");
        };
    }

    private int validatedLimit(long limit) {
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit must be a positive int, got: " + limit);
        }
        return (int) limit;
    }

    private Duration validatedWindow(long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("window_millis must be positive, got: " + windowMillis);
        }
        return Duration.ofMillis(windowMillis);
    }
}
