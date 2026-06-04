package com.flowgate.core;

/**
 * Abstraction over System.nanoTime() so tests can control time
 * without sleeping. Production code passes System::nanoTime.
 */
@FunctionalInterface
public interface NanoClock {
    long nanoTime();
}