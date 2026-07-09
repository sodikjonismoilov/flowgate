package com.flowgate.service.dto;


/**
 * Request body for {@code POST /v1/completions}.
 *
 * <p>Deliberately minimal - this demo doesn't call a real LLM. the point is to
 * demonstrate rate limiting behaviour, not to build full completions API.
 * {@code prompt} is the only field that matters for the demo.
 *
 * @param prompt the text prompt a real backend would send to an LLM
 */
public record CompletionRequest(String prompt) {
}
