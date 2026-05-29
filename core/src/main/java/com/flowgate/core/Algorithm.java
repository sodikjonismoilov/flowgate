package com.flowgate.core;

/**
 * The four canonical rate-limiting algorithms.
 *
 * <p>Each algorithm has different tradeoffs in memory cost, accuracy, and burst behavior.
 * The benchmark module measures all four so the comparison is grounded in real numbers,
 * not theory. The README's Engineering Decisions section will explain the choice of default.
 */
public enum Algorithm {

    /**
     * Token bucket: requests consume tokens; bucket refills at a constant rate.
     * <ul>
     *   <li>Allows bursts up to the bucket capacity.
     *   <li>O(1) memory per key — just two values: current tokens + last refill time.
     *   <li>Industry default — AWS API Gateway, Stripe, and Cloudflare all use variants.
     * </ul>
     */
    TOKEN_BUCKET,

    /**
     * Leaky bucket: requests enter a queue and drain at a fixed rate.
     * <ul>
     *   <li>Smooths traffic — no bursts allowed, output rate is always constant.
     *   <li>Good for protecting downstream systems that cannot handle sudden spikes.
     *   <li>Memory scales with queue depth.
     * </ul>
     */
    LEAKY_BUCKET,

    /**
     * Sliding window log: stores the timestamp of every request in the current window.
     * <ul>
     *   <li>Exact accuracy — no approximation or edge-case spikes.
     *   <li>O(n) memory per key where n = number of requests in the window.
     *   <li>Use when precision matters more than memory: billing, compliance, auditing.
     * </ul>
     */
    SLIDING_WINDOW_LOG,

    /**
     * Sliding window counter: blends two fixed-window counters to approximate a
     * true sliding window using a weighted formula.
     * <ul>
     *   <li>~99% accurate at O(1) memory — best general-purpose production choice.
     *   <li>The math is non-obvious: make sure you can explain the weighted blend
     *       in an interview before using this on your resume.
     *   <li>Cloudflare's published rate limiter uses this algorithm.
     * </ul>
     */
    SLIDING_WINDOW_COUNTER
}
