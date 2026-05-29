package com.flowgate.library.exception;

import com.flowgate.core.model.RateLimitResult;

/**
 * Thrown by the @RateLimit AOP aspect when a rate limit is exceeded.
 *
 * <p>Callers should register a Spring {@code @ControllerAdvice} or
 * {@code @ExceptionHandler} that catches this and returns a proper
 * HTTP 429 response with rate-limit headers:
 *
 * <pre>{@code
 * @ExceptionHandler(RateLimitExceededException.class)
 * public ResponseEntity<Void> handleRateLimit(RateLimitExceededException ex) {
 *     RateLimitResult r = ex.getResult();
 *     return ResponseEntity
 *         .status(HttpStatus.TOO_MANY_REQUESTS)
 *         .header("X-RateLimit-Limit",     String.valueOf(r.limit()))
 *         .header("X-RateLimit-Remaining", String.valueOf(r.remaining()))
 *         .header("Retry-After",           String.valueOf(r.retryAfter().toSeconds()))
 *         .build();
 * }
 * }</pre>
 *
 * <p>This is a RuntimeException (unchecked) so controllers don't need
 * {@code throws} declarations. Spring's exception handling infrastructure
 * picks it up transparently.
 */
public class RateLimitExceededException extends RuntimeException {

    // Carry the full result so the exception handler can populate response headers
    private final RateLimitResult result;

    public RateLimitExceededException(RateLimitResult result) {
        super(String.format(
                "Rate limit exceeded. Retry after %ds. (limit=%d)",
                result.retryAfter().toSeconds(),
                result.limit()
        ));
        this.result = result;
    }

    public RateLimitResult getResult() {
        return result;
    }
}
