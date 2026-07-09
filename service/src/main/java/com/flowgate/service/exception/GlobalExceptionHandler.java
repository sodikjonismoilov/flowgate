package com.flowgate.service.exception;



import com.flowgate.core.model.RateLimitResult;
import com.flowgate.library.exception.RateLimitExceededException;
import com.flowgate.service.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link RateLimitExceededException} into a proper HTTP 429 response.
 *
 * <p>This is the last stop in the request flow described in
 * {@link com.flowgate.library.aspect.RateLimitAspect}: the aspect throws this
 * exception when {@code tryAcquire()} rejects a request, and Spring's exception
 * handling infrastructure routes it here before it ever reaches the client.
 *
 * <p>The response carries the standard rate-limit headers so a well-behaved
 * client (or a demo using curl -i) can see exactly why it was rejected and
 * when to retry. It also carries a JSON body mirroring those facts, for
 * clients that read the payload rather than the headers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        RateLimitResult result = ex.getResult();
        long retryAfterSeconds = result.retryAfter().toSeconds();

        ErrorResponse body = new ErrorResponse(
                "rate_limit_exceeded",
                "Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.",
                result.limit(),
                result.remaining(),
                retryAfterSeconds);

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Limit", String.valueOf(result.limit()))
                .header("X-RateLimit-Remaining", String.valueOf(result.remaining()))
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(body);
    }
}