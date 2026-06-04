package com.flowgate.core.inmemory;

import com.flowgate.core.NanoClock;
import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class TokenBucketRateLimiter implements RateLimiter {

    private record BucketState(long tokens, long lastRefillNanos) {}

    private final RateLimiterConfig config;
    private final ConcurrentHashMap<String, AtomicReference<BucketState>> buckets;
    private final double refillTokensPerNano; // how many tokens per nanosecond

    private final NanoClock clock;

    public TokenBucketRateLimiter(RateLimiterConfig config) {
        this(config, System::nanoTime);
    }

    public TokenBucketRateLimiter(RateLimiterConfig config, NanoClock clock) {
        this.config = config;
        this.buckets = new ConcurrentHashMap<>();
        this.clock = clock;
        // convert: limit tokens per window → tokens per nanosecond
        this.refillTokensPerNano = (double) config.limit() / config.window().toNanos();
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        AtomicReference<BucketState> ref = buckets.computeIfAbsent(
                key,
                k -> new AtomicReference<>(
                        new BucketState(config.burstCapacity(), clock.nanoTime())
                )
        );

        while (true) {
            BucketState current = ref.get();

            // Calculate how many tokens to add based on time passed
            long elapsedNanos = clock.nanoTime() - current.lastRefillNanos();
            long tokensToAdd = (long) (elapsedNanos * refillTokensPerNano);

            long newTokens = Math.min(config.burstCapacity(), current.tokens() + tokensToAdd);

            if (newTokens < tokens) {
                // Not enough tokens, reject the request
                return RateLimitResult.rejected(
                        config.limit(),                        // limit
                        Instant.now().plus(config.window()),   // resetAt
                        config.window()                        // retryAfter
                );
            }

            // Try to update the bucket state atomically
            BucketState newState = new BucketState(newTokens - tokens, clock.nanoTime());
            if (ref.compareAndSet(current, newState)) {
                // success
                return RateLimitResult.allowed(
                        config.limit(),
                        newState.tokens(),
                        Instant.now().plus(config.window())
                );
            }
            // If we failed to update, another thread modified the state, so we need to retry

        }
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }
}