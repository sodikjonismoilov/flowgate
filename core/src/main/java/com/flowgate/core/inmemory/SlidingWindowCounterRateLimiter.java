package com.flowgate.core.inmemory;

import com.flowgate.core.NanoClock;
import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class SlidingWindowCounterRateLimiter implements RateLimiter {

    private static final class WindowState {
        private long currentCount;
        private long previousCount;
        private long windowStart;

        private WindowState(long currentCount, long previousCount, long windowStart) {
            this.currentCount = currentCount;
            this.previousCount = previousCount;
            this.windowStart = windowStart;
        }
    }

    private final RateLimiterConfig config;
    private final NanoClock clock;
    private final ConcurrentHashMap<String, WindowState> buckets;

    public SlidingWindowCounterRateLimiter(RateLimiterConfig config, NanoClock clock) {
        this.config = config;
        this.clock = clock;
        this.buckets = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unused")
    public SlidingWindowCounterRateLimiter(RateLimiterConfig config) {
        this(config, System::nanoTime);
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
        int limit = config.limit();

        AtomicReference<RateLimitResult> result = new AtomicReference<>();

        buckets.compute(key, (k, currentState) -> {
            if (currentState == null) {
                currentState = new WindowState(0, 0, now);
            }

            if (now - currentState.windowStart >= windowNanos) {
                long numWindowsPassed = (now - currentState.windowStart) / windowNanos;
                currentState.previousCount = (numWindowsPassed == 1) ? currentState.currentCount : 0;
                currentState.currentCount = 0;
                currentState.windowStart += numWindowsPassed * windowNanos;
            }

            double elapsedFraction = Math.clamp(
                    (double) (now - currentState.windowStart) / windowNanos,
                    0.0d,
                    1.0d);
            long estimatedCount = currentState.currentCount +
                    (long) Math.floor(currentState.previousCount * (1.0 - elapsedFraction));

            if (estimatedCount + tokens > limit) {
                long nanosUntilReset = Math.max(0, windowNanos - (now - currentState.windowStart));
                Duration retryAfter = Duration.ofNanos(nanosUntilReset);
                result.set(RateLimitResult.rejected(
                        limit,
                        Instant.now().plus(retryAfter),
                        retryAfter
                ));
                return currentState;
            }

            currentState.currentCount += tokens;
            long remaining = Math.max(0, limit - (currentState.currentCount +
                    (long) Math.floor(currentState.previousCount * (1.0 - elapsedFraction))));
            long nanosUntilReset = Math.max(0, windowNanos - (now - currentState.windowStart));
            result.set(RateLimitResult.allowed(
                    limit,
                    remaining,
                    Instant.now().plusNanos(nanosUntilReset)
            ));
            return currentState;
        });

        return result.get();
    }

}