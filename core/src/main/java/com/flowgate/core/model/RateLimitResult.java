package com.flowgate.core.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable result of a single rate-limit check.
 *
 * <p>Contains everything needed to:
 * <ol>
 *   <li>Decide whether to allow or reject the request ({@link #allowed()})</li>
 *   <li>Populate standard HTTP rate-limit response headers:
 *       <ul>
 *         <li>{@code X-RateLimit-Limit}: {@link #limit()}</li>
 *         <li>{@code X-RateLimit-Remaining}: {@link #remaining()}</li>
 *         <li>{@code X-RateLimit-Reset}: {@link #resetAt()} (as epoch seconds)</li>
 *         <li>{@code Retry-After}: {@link #retryAfter()} (only on 429 responses)</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>We use a Java {@code record} here because the result is purely data —
 * no behavior, no mutable state. Records give us equals/hashCode/toString for free.
 */
public record RateLimitResult(
        boolean  allowed,     // true = request should proceed; false = reject with 429
        long     limit,       // the configured max requests per window
        long     remaining,   // permits left in the current window (0 when rejected)
        Instant  resetAt,     // when the current window resets (useful for X-RateLimit-Reset)
        Duration retryAfter   // how long to wait before retrying (only meaningful when !allowed)
) {

    /**
     * Factory method for an allowed result.
     * retryAfter is Duration.ZERO — the caller should not include a Retry-After header.
     */
    public static RateLimitResult allowed(long limit, long remaining, Instant resetAt) {
        return new RateLimitResult(true, limit, remaining, resetAt, Duration.ZERO);
    }

    /**
     * Factory method for a rejected result.
     * remaining is 0 — no more permits available.
     */
    public static RateLimitResult rejected(long limit, Instant resetAt, Duration retryAfter) {
        return new RateLimitResult(false, limit, 0, resetAt, retryAfter);
    }
}
