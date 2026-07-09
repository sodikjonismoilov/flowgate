package com.flowgate.service.dto;

/**
 * JSON error body returned when a request is rejected.
 *
 * <p>Currently used for the HTTP 429 rate-limit case (see
 * {@link com.flowgate.service.exception.GlobalExceptionHandler}). The same
 * quota facts are also exposed as response headers ({@code X-RateLimit-*},
 * {@code Retry-After}); this body simply makes them visible to clients that
 * read the payload rather than the headers.
 *
 * @param error      short, stable machine-readable error code
 * @param message    human-readable explanation
 * @param limit      the configured max requests per window
 * @param remaining  permits left in the current window (0 when rejected)
 * @param retryAfter seconds the client should wait before retrying
 */
public record ErrorResponse(
        String error,
        String message,
        long limit,
        long remaining,
        long retryAfter) {
}