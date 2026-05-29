package com.flowgate.core;

import java.time.Duration;

/**
 * Immutable configuration for a rate limiter instance.
 *
 * <h3>Use the factory methods</h3>
 * Each factory method encodes the correct defaults and constraints for its algorithm:
 * <pre>
 *   RateLimiterConfig.tokenBucket(100, Duration.ofMinutes(1))
 *   RateLimiterConfig.leakyBucket(50, Duration.ofSeconds(10))
 *   RateLimiterConfig.slidingWindow(SLIDING_WINDOW_COUNTER, 200, Duration.ofMinutes(1))
 * </pre>
 *
 * <h3>Why burstCapacity is separate from limit</h3>
 * For a token bucket, {@code limit} = tokens that refill per window.
 * {@code burstCapacity} = max tokens the bucket can hold at any time.
 * Setting burstCapacity = 2 × limit lets a user "save up" tokens during a quiet
 * period and then burst. This is a deliberate product decision, not just a technical one.
 * For sliding window algorithms there is no burst concept, so burstCapacity == limit.
 */
public record RateLimiterConfig(
        Algorithm algorithm,
        int limit,           // requests (or tokens) allowed per window
        Duration window,     // length of the rolling time window
        int burstCapacity    // max tokens the bucket can hold (token bucket only)
) {

    /**
     * Compact constructor: validates invariants before the record is created.
     * This runs as part of the generated canonical constructor — it's Java's
     * idiomatic way to validate record fields.
     */
    public RateLimiterConfig {
        if (limit <= 0)
            throw new IllegalArgumentException("limit must be > 0, got: " + limit);
        if (window == null || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("window must be positive, got: " + window);
        if (burstCapacity < limit)
            throw new IllegalArgumentException(
                "burstCapacity (" + burstCapacity + ") must be >= limit (" + limit + ")");
    }

    // ─── Factory methods ──────────────────────────────────────────────────────

    /** Token bucket with burst capacity = 2x the per-window limit (a sensible default). */
    public static RateLimiterConfig tokenBucket(int limit, Duration window) {
        return new RateLimiterConfig(Algorithm.TOKEN_BUCKET, limit, window, limit * 2);
    }

    /** Token bucket with an explicit burst capacity. burstCapacity must be >= limit. */
    public static RateLimiterConfig tokenBucket(int limit, Duration window, int burstCapacity) {
        return new RateLimiterConfig(Algorithm.TOKEN_BUCKET, limit, window, burstCapacity);
    }

    /** Leaky bucket: fixed processing rate, no burst allowed. */
    public static RateLimiterConfig leakyBucket(int limit, Duration window) {
        return new RateLimiterConfig(Algorithm.LEAKY_BUCKET, limit, window, limit);
    }

    /**
     * Sliding window log or counter. No burst concept: burstCapacity == limit.
     *
     * @param algorithm must be SLIDING_WINDOW_LOG or SLIDING_WINDOW_COUNTER
     */
    public static RateLimiterConfig slidingWindow(Algorithm algorithm, int limit, Duration window) {
        if (algorithm == Algorithm.TOKEN_BUCKET || algorithm == Algorithm.LEAKY_BUCKET) {
            throw new IllegalArgumentException(
                "Use tokenBucket() or leakyBucket() for " + algorithm);
        }
        return new RateLimiterConfig(algorithm, limit, window, limit);
    }
}
