package com.flowgate.service.dto;


/**
 * Response body for {@code POST /v1/completions}.
 *
 * <p>{@code completion} is a stubbed response — this demo has no real LLM backend.
 * {@code tenantId} is echoed back so a demo can visibly show two different API
 * keys getting independent quotas, even though the response body itself doesn't
 * carry quota numbers.
 *
 * <p>Live quota (remaining requests, retry-after) is surfaced via response
 * headers instead — see {@link com.flowgate.service.exception.GlobalExceptionHandler}
 * for the 429 case. The {@code RateLimitAspect} intercepts the call before this
 * method's body runs, so the controller itself has no direct access to the
 * {@code RateLimitResult} on the success path; only the rejection path carries
 * it, via {@code RateLimitExceededException}.
 *
 * @param completion stubbed completion text
 * @param tenantId   the API key that identified this request
 */
public record CompletionResponse(String completion, String tenantId) {
}