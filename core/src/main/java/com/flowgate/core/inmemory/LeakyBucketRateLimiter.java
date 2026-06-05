package com.flowgate.core.inmemory;

import com.flowgate.core.NanoClock;
import com.flowgate.core.RateLimiter;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


public class LeakyBucketRateLimiter implements RateLimiter {
    private record BucketState(long lastLeakNanos, long currentLevel) {}

    private final RateLimiterConfig config;
    private final ConcurrentHashMap<String, AtomicReference<BucketState>> buckets;
    private final double leakRatePerNano; // requests that drain per nanosecond
    private final NanoClock clock;

    public LeakyBucketRateLimiter(RateLimiterConfig config) {
        this(config, System::nanoTime);
    }

    public LeakyBucketRateLimiter(RateLimiterConfig config, NanoClock clock) {
        this.config = config;
        this.clock = clock;
        this.buckets = new ConcurrentHashMap<>();
        //leak rate: config limit() requests drain per window.
        this.leakRatePerNano = (double) config.limit() / config.window().toNanos();
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        AtomicReference<BucketState> ref = buckets.computeIfAbsent(
                key,
                k -> new AtomicReference<>(new BucketState(clock.nanoTime(), 0))
        );

        while (true) {
            BucketState current = ref.get();

            //todo1: compute how much has leaked since last update
            //leaked = elapsedNanos * leakRatePerNano
            //newLevel = max(0, currentLevel - leaked)
            long elapsedNanos = clock.nanoTime() - current.lastLeakNanos();
            long leaked = (long) (elapsedNanos * leakRatePerNano);
            long newLevel = Math.max(0, current.currentLevel() - leaked);

            //todo2: check if adding tokens would exceed capacity (config.limit())
            //if yer: reject
            // if no: allow, new level = newLevel + tokens
            if (newLevel + tokens > config.limit()) {
                return RateLimitResult.rejected(
                        config.limit(),
                        Instant.now().plus(config.window()),   // resetAt
                        config.window()
                );
            }
            // replace line 42

            if (ref.compareAndSet(current, new BucketState(clock.nanoTime(), newLevel + tokens))) {
                return RateLimitResult.allowed(
                        config.limit(),
                        config.limit() - (newLevel + tokens),
                        Instant.now().plus(config.window())
                );
            }

        }
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }
}
