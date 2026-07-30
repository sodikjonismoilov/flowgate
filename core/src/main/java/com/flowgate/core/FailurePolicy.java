package com.flowgate.core;

/**
 * Determines what a RateLimiter does when its backend Redis is unreachable
 * instead of letting the caller crash on an unhandled exception.
 */

public enum FailurePolicy {

    /** Treat the request as allowed. An outage becomes "unlimited traffic." */
    FAIL_OPEN,
    /** Treat the request as rejected. An outage becomes "no traffic." */
    FAIL_CLOSED
}
