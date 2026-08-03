package com.flowgate.library.aspect;

import com.flowgate.core.Algorithm;
import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import com.flowgate.core.redis.RedisLeakyBucketRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowCounterRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowLogRateLimiter;
import com.flowgate.core.redis.RedisTokenBucketRateLimiter;
import com.flowgate.library.annotation.RateLimit;
import com.flowgate.library.exception.RateLimitExceededException;
import io.lettuce.core.RedisClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import com.flowgate.core.FailSafeRateLimiter;
import com.flowgate.core.FailurePolicy;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * AOP aspect that enforces @RateLimit on any Spring-managed method.
 *
 * <h3>Execution flow</h3>
 * <pre>
 *   HTTP request
 *     → Spring MVC
 *     → RateLimitAspect.enforce()
 *         1. Resolve the rate-limit key (SpEL or method signature)
 *         2. Look up or create the RateLimiter for this annotation's config
 *         3. Call tryAcquire(key) → Lua script executes atomically on Redis
 *         4. allowed → joinPoint.proceed() → controller runs
 *            rejected → throw RateLimitExceededException → 429 response
 * </pre>
 *
 * <h3>Limiter caching</h3>
 * Each unique combination of (algorithm, limit, window) gets its own
 * {@link RateLimiter} instance, created on first use and cached. Most
 * applications annotate a handful of endpoints, so this map stays small.
 * {@link ConcurrentHashMap#computeIfAbsent} ensures only one instance
 * is created per config even under concurrent first-use.
 *
 * <h3>SpEL key resolution</h3>
 * The {@code key()} attribute on @RateLimit is a SpEL expression evaluated
 * against the method's arguments. Spring's {@link MethodBasedEvaluationContext}
 * makes parameter names available by name (e.g. {@code "#userId"}).
 * If {@code key()} is empty, the method's fully qualified signature is used,
 * giving per-endpoint limiting rather than per-entity limiting.
 */

@Aspect
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RedisClient redisClient;
    private final FailurePolicy failurePolicy;
    private final MeterRegistry meterRegistry;

    /**
     * Cache: "ALGORITHM:limit:windowString" → RateLimiter instance.
     * Different @RateLimit annotations with different limits or windows
     * each get their own limiter instance backed by a distinct Redis key namespace.
     */
    private final ConcurrentHashMap<String, RateLimiter> limiterCache = new ConcurrentHashMap<>();

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    public RateLimitAspect(RedisClient redisClient, FailurePolicy failurePolicy, MeterRegistry meterRegistry ) {
        this.redisClient = redisClient;
        this.failurePolicy = failurePolicy;
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(rateLimit)")
    public Object enforce(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = resolveKey(joinPoint, rateLimit);
        RateLimiter limiter = getOrCreateLimiter(rateLimit);
        String algorithmTag = rateLimit.algorithm().name();

        Timer.Sample sample = Timer.start(meterRegistry);
        RateLimitResult result = limiter.tryAcquire(key);
        sample.stop(meterRegistry.timer("flowgate.check.duration", "algorithm", algorithmTag));

        meterRegistry.counter("flowgate.check.duration", "algorithm", algorithmTag,
                "outcome", result.allowed() ? "allowed" : "denied").increment();

        log.debug("Rate limit check: key={}, algorithm={}, allowed={}, remaining={}",
                key, rateLimit.algorithm(), result.allowed(), result.remaining());

        if (result.allowed()) {
            return joinPoint.proceed();
        }

        throw new RateLimitExceededException(result);
    }

    /**
     * Resolves the rate-limit key from the @RateLimit annotation.
     *
     * <p>If {@code key()} is empty: use the method's fully qualified signature.
     * This gives per-endpoint limiting — every caller shares one counter.
     *
     * <p>If {@code key()} is a SpEL expression (e.g. {@code "#userId"}):
     * evaluate it against the method arguments. This gives per-entity limiting —
     * each unique value gets its own counter in Redis.
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (rateLimit.key().isEmpty()) {
            // Per-endpoint limiting: use the method signature as the key.
            return joinPoint.getSignature().toLongString();
        }

        // Per-entity limiting: evaluate SpEL against the method arguments.
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // MethodBasedEvaluationContext makes parameter names available as SpEL variables.
        // e.g. "#userId" resolves to the value of the `userId` method parameter.
        EvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(),
                method,
                joinPoint.getArgs(),
                paramDiscoverer
        );

        Object value = spelParser.parseExpression(rateLimit.key()).getValue(context);
        return value != null ? value.toString() : "null";
    }

    /**
     * Returns a cached {@link RateLimiter} for the given annotation config,
     * creating one on first use. The cache key encodes all fields that affect
     * limiter behaviour so different annotations get different instances.
     */
    private RateLimiter getOrCreateLimiter(RateLimit rateLimit) {
        String cacheKey = rateLimit.algorithm() + ":" + rateLimit.limit() + ":" + rateLimit.window();
        return limiterCache.computeIfAbsent(cacheKey, k -> createLimiter(rateLimit));
    }

    private RateLimiter createLimiter(RateLimit rateLimit) {
        Duration window = Duration.parse(rateLimit.window());
        int limit = rateLimit.limit();
        Algorithm algorithm = rateLimit.algorithm();

        RateLimiterConfig config = switch (algorithm) {
            case TOKEN_BUCKET    -> RateLimiterConfig.tokenBucket(limit, window);
            case LEAKY_BUCKET    -> RateLimiterConfig.leakyBucket(limit, window);
            case SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER ->
                    RateLimiterConfig.slidingWindow(algorithm, limit, window);
        };

        Supplier<RateLimiter> delegateSupplier = () -> switch (algorithm) {
            case TOKEN_BUCKET         -> new RedisTokenBucketRateLimiter(config, redisClient);
            case LEAKY_BUCKET         -> new RedisLeakyBucketRateLimiter(config, redisClient);
            case SLIDING_WINDOW_LOG   -> new RedisSlidingWindowLogRateLimiter(config, redisClient);
            case SLIDING_WINDOW_COUNTER -> new RedisSlidingWindowCounterRateLimiter(config, redisClient);
        };

        return new FailSafeRateLimiter(config, delegateSupplier, failurePolicy);
    }
}