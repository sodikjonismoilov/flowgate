package com.flowgate.core;

import com.flowgate.core.model.RateLimitResult;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Decorator that wraps any {@link RateLimiter} and defines behavior when the
 * delegate throws — e.g. because Redis is unreachable or times out.
 *
 * <p>Applies uniformly to every algorithm since it wraps the {@link RateLimiter}
 * interface itself rather than modifying each implementation individually.
 *
 * <p>Deliberately catches only {@link RedisException} (Lettuce's exception
 * hierarchy for connection failures, timeouts, and command errors) — not a
 * blanket {@code RuntimeException} — so a genuine bug elsewhere isn't silently
 * reinterpreted as "Redis is down."
 *
 * <p>The delegate is built lazily from {@code delegateSupplier} on first use,
 * inside the same try/catch as {@code tryAcquire}. Redis-backed limiters open
 * their connection in their constructor, so if the delegate were built eagerly
 * (before this class exists to catch anything) a connection failure at
 * construction time would bypass the failure policy entirely and surface as an
 * unhandled exception instead of a fail-open/fail-closed decision.
 */
public class FailSafeRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailSafeRateLimiter.class);

    private final RateLimiterConfig config;
    private final Supplier<RateLimiter> delegateSupplier;
    private final FailurePolicy policy;
    private volatile RateLimiter delegate;

    public FailSafeRateLimiter(RateLimiterConfig config, Supplier<RateLimiter> delegateSupplier, FailurePolicy policy) {
        this.config = config;
        this.delegateSupplier = delegateSupplier;
        this.policy = policy;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public RateLimitResult tryAcquire(String key, int tokens) {
        try {
            return delegate().tryAcquire(key, tokens);
        } catch (RedisException e) {
            log.warn("Rate limiter backend unreachable for key={}, applying {} policy", key, policy, e);
            return switch (policy) {
                case FAIL_OPEN -> RateLimitResult.allowed(
                        config.limit(), config.limit(), Instant.now().plus(config.window()));
                case FAIL_CLOSED -> RateLimitResult.rejected(
                        config.limit(), Instant.now().plus(config.window()), Duration.ofSeconds(1));
            };
        }
    }

    private RateLimiter delegate() {
        RateLimiter d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    d = delegateSupplier.get();
                    delegate = d;
                }
            }
        }
        return d;
    }

    @Override
    public RateLimiterConfig config() {
        return config;
    }
}