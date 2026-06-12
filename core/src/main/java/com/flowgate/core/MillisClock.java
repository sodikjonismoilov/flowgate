package com.flowgate.core;

/**
 * Millisecond-precision clock abstraction for Redis-backed rate limiters.
 *
 * <p>Mirrors {@link NanoClock}, but in milliseconds rather than nanoseconds.
 * Redis-backed implementations pass the "current time" into Lua scripts as
 * an argument, and Lua's doubles cannot safely represent epoch nanoseconds
 * (see {@code RedisTokenBucketRateLimiter} for the precision argument).
 * Milliseconds since epoch fit comfortably within a double's 53-bit mantissa.
 *
 * <p>Production code uses {@code System::currentTimeMillis}. Tests inject a
 * fake clock so refill behaviour can be asserted deterministically without
 * {@code Thread.sleep()}.
 */
@FunctionalInterface
public interface MillisClock {
    long millis();
}