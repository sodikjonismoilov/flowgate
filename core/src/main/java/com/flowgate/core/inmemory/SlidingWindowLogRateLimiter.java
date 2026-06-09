package com.flowgate.core.inmemory;

import com.flowgate.core.RateLimiter;
import com.flowgate.core.NanoClock;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


public class SlidingWindowLogRateLimiter implements RateLimiter {

    private final RateLimiterConfig config;
    private final NanoClock clock;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> windows;

    public SlidingWindowLogRateLimiter(RateLimiterConfig config) {
        this(config, System::nanoTime);
    }

    public SlidingWindowLogRateLimiter(RateLimiterConfig config, NanoClock clock) {
        this.config = config;
        this.clock = clock;
        this.windows = new ConcurrentHashMap<>();
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        long now = clock.nanoTime();
        long windowNanos = config.window().toNanos();
        long cutoff = now - windowNanos;

        AtomicReference<RateLimitResult> result = new AtomicReference<>();

        // compute() holds a per-bucket lock, giving us atomic check-then-act per key
        // without blocking unrelated keys. Returning null removes the entry (lazy eviction).
        windows.compute(key, (k, window) -> {
            if (window == null) window = new ArrayDeque<>();

            while (!window.isEmpty() && window.peekFirst() <= cutoff) {
                window.pollFirst();
            }

            if (window.size() + tokens > config.limit()) {
                // oldest entry's expiry is the soonest the caller can retry
                long nanosUntilReset = window.isEmpty()
                        ? 0
                        : window.peekFirst() + windowNanos - now;
                nanosUntilReset = Math.max(0, nanosUntilReset);
                Duration retryAfter = Duration.ofNanos(nanosUntilReset);
                result.set(RateLimitResult.rejected(
                        config.limit(),
                        Instant.now().plus(retryAfter),
                        retryAfter
                ));
            } else {
                for (int i = 0; i < tokens; i++) {
                    window.addLast(now);
                }
                Long oldest = window.peekFirst();
                long nanosUntilReset = oldest == null ? 0 : Math.max(0, oldest + windowNanos - now);
                result.set(RateLimitResult.allowed(
                        config.limit(),
                        config.limit() - window.size(),
                        Instant.now().plusNanos(nanosUntilReset)
                ));
            }

            return window.isEmpty() ? null : window;
        });

        return result.get();
    }
}