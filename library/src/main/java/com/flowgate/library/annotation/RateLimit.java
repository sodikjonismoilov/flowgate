package com.flowgate.library.annotation;

import com.flowgate.core.Algorithm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Apply distributed rate limiting to any Spring-managed method.
 *
 * <h3>Basic usage</h3>
 * <pre>{@code
 * @RateLimit(algorithm = Algorithm.TOKEN_BUCKET, key = "#userId", limit = 100, window = "PT1M")
 * @GetMapping("/api/data")
 * public Data getData(@RequestParam String userId) { ... }
 * }</pre>
 *
 * <h3>Key expressions (SpEL)</h3>
 * The {@link #key()} attribute is evaluated as a Spring Expression Language (SpEL)
 * expression in the method execution context. Parameter names are available by name:
 * <ul>
 *   <li>{@code "#userId"}            — the value of the userId parameter
 *   <li>{@code "#request.remoteAddr"} — IP from an HttpServletRequest parameter
 *   <li>{@code "'global'"}           — hard-coded string (global endpoint limit)
 * </ul>
 * If {@link #key()} is empty, the fully qualified method signature is used as the key,
 * giving you per-endpoint limiting rather than per-entity limiting.
 *
 * <h3>What happens when the limit is exceeded</h3>
 * The AOP aspect throws {@code RateLimitExceededException} (Week 4), which maps to
 * HTTP 429 Too Many Requests with a {@code Retry-After} header.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME) // must be RUNTIME so AOP can read it via reflection
@Documented
public @interface RateLimit {

    /** Which algorithm to use. Defaults to token bucket — the industry standard. */
    Algorithm algorithm() default Algorithm.TOKEN_BUCKET;

    /**
     * SpEL expression evaluated at runtime to compute the rate-limit key.
     * Empty string means "use the fully qualified method signature" (per-endpoint limit).
     */
    String key() default "";

    /** Maximum requests allowed per window. */
    int limit() default 100;

    /**
     * Time window as an ISO-8601 duration string.
     * Examples: {@code "PT1M"} = 1 minute, {@code "PT1H"} = 1 hour, {@code "PT30S"} = 30 seconds.
     */
    String window() default "PT1M";
}
