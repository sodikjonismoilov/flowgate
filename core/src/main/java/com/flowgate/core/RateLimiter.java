package com.flowgate.core;

import com.flowgate.core.model.RateLimitResult;

/**
 * Core rate limiter contract shared by all four algorithm implementations.
 *
 * <h3>Thread safety requirement</h3>
 * Every implementation of this interface MUST be thread-safe. The AOP layer
 * and the service layer both call these from concurrent request threads.
 * <ul>
 *   <li><b>In-memory implementations</b> use {@link java.util.concurrent.atomic.AtomicLong}
 *       and other JDK concurrent primitives. No locks needed for simple counters.
 *   <li><b>Redis implementations</b> delegate atomicity to Lua scripts. Redis executes
 *       each Lua script as a single atomic unit — no two scripts interleave. This
 *       eliminates the race condition that would exist with a GET-then-SET pattern
 *       across multiple Redis commands.
 * </ul>
 *
 * <h3>Key semantics</h3>
 * The {@code key} identifies the rate-limited entity. Different keys are tracked
 * independently: user A consuming quota does not affect user B.
 * <pre>
 *   "user:42"              per-user limiting
 *   "ip:203.0.113.1"       per-IP limiting
 *   "apikey:abc123"        per-API-key limiting
 *   "POST:/send:user:42"   per-endpoint-per-user limiting
 * </pre>
 */
public interface RateLimiter {

    /**
     * Try to acquire one unit of capacity for the given key.
     *
     * @param key identifies the rate-limited entity (user ID, IP address, API key, etc.)
     * @return result indicating whether the request is allowed and remaining capacity
     */
    RateLimitResult tryAcquire(String key);

    /**
     * Try to acquire {@code tokens} units of capacity.
     * <p>
     * Useful when one logical operation should consume multiple credits —
     * for example, uploading a 10 MB file might cost 10 tokens to reflect
     * its actual resource usage compared to a simple API call.
     *
     * @param key    identifies the rate-limited entity
     * @param tokens number of tokens to consume; must be positive
     * @return result indicating whether the request is allowed
     */
    RateLimitResult tryAcquire(String key, int tokens);

    /**
     * Returns the configuration this limiter was constructed with.
     * Exposed so the AOP layer can read limits without reflection.
     */
    RateLimiterConfig config();
}
