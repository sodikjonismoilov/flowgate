package com.flowgate.library.aspect;

import com.flowgate.library.annotation.RateLimit;
import com.flowgate.library.exception.RateLimitExceededException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AOP aspect that intercepts @RateLimit-annotated methods and enforces limits.
 *
 * <p>How @Around advice works:
 * When Spring sees a call to an annotated method, it routes the call through
 * this advice first. The advice decides whether to:
 * <ol>
 *   <li>Call {@code joinPoint.proceed()} → execute the original method</li>
 *   <li>Throw an exception → short-circuit without running the method</li>
 * </ol>
 *
 * <p>Full execution flow (implemented in Week 4):
 * <pre>
 *   HTTP request
 *       → Spring MVC
 *       → RateLimitAspect.enforce()        ← we are here
 *           → evaluate SpEL key expression
 *           → call RateLimiter.tryAcquire()
 *               → Redis Lua script (atomic check + increment)
 *           → if allowed: joinPoint.proceed() → controller method
 *           → if rejected: throw RateLimitExceededException
 *       → GlobalExceptionHandler catches it, returns 429
 * </pre>
 *
 * <p><b>TODO (Week 4):</b> Wire in SpEL evaluation and RateLimiter implementations.
 */
@Aspect
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    /**
     * Intercepts any method annotated with @RateLimit.
     *
     * @param joinPoint the intercepted method call — call proceed() to run it
     * @param rateLimit the annotation instance, with its configured values
     */
    @Around("@annotation(rateLimit)")
    public Object enforce(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // TODO (Week 4): implement the following steps
        //
        // Step 1: Resolve the rate-limit key using SpEL
        //   Use Spring's ExpressionParser to evaluate rateLimit.key()
        //   against the method arguments (joinPoint.getArgs()).
        //
        // Step 2: Parse the window string into a Duration
        //   e.g. "1m" → Duration.ofMinutes(1), "30s" → Duration.ofSeconds(30)
        //
        // Step 3: Select the correct RateLimiter implementation
        //   based on rateLimit.algorithm() — inject a Map<Algorithm, RateLimiter>
        //
        // Step 4: Call tryAcquire()
        //   RateLimitResult result = rateLimiter.tryAcquire(key, rateLimit.limit(), window);
        //
        // Step 5: Allow or reject
        //   if (result.allowed()) return joinPoint.proceed();
        //   else throw new RateLimitExceededException(result);

        log.debug("@RateLimit intercepted: method={}, algorithm={}, key={}, limit={}/{}",
                joinPoint.getSignature().toShortString(),
                rateLimit.algorithm(),
                rateLimit.key(),
                rateLimit.limit(),
                rateLimit.window());

        // Temporary pass-through until Week 4 implementation
        return joinPoint.proceed();
    }
}
