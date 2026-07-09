package com.flowgate.service.controller;

import com.flowgate.core.Algorithm;
import com.flowgate.library.annotation.RateLimit;
import com.flowgate.service.dto.CompletionRequest;
import com.flowgate.service.dto.CompletionResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demonstrates flowgate as a multi-tenant AI API gateway.
 *
 * <h3>The demo</h3>
 * Multiple tenants (identified by their own API key) call this single endpoint.
 * Each tenant gets an independent, Redis-backed rate limit — one tenant exhausting
 * their quota has zero effect on any other tenant. This is exactly the behaviour
 * proven by the {@code differentKeysAreTrackedIndependently} tests written for
 * every Redis-backed algorithm in {@code flowgate-core}, now visible end-to-end
 * over real HTTP.
 *
 * <h3>How the key is resolved</h3>
 * {@code key = "#apiKey"} is a SpEL expression evaluated by {@code RateLimitAspect}
 * against this method's arguments. {@code #apiKey} matches the {@code apiKey}
 * parameter name below (populated from the {@code X-API-Key} header), so each
 * distinct API key produces a distinct Redis key — e.g.
 * {@code flowgate:token_bucket:acme-corp-key} vs {@code flowgate:token_bucket:widgetco-key}.
 *
 * <h3>No real LLM backend</h3>
 * This is a rate-limiting demo, not an LLM integration. {@code complete()} returns
 * a stubbed response. Swapping in a real backend call is a one-line change inside
 * the method body — the rate limiting behaviour above it is unaffected either way.
 */
@RestController
public class LlmGatewayController {

    /**
     * Token bucket: allows short bursts up to 2x the per-minute limit (the
     * default burst capacity from {@code RateLimiterConfig.tokenBucket()}),
     * which fits an LLM API use case where a client might fire a few requests
     * in quick succession, then idle.
     */
    @RateLimit(algorithm = Algorithm.TOKEN_BUCKET, key = "#apiKey", limit = 10, window = "PT1M")
    @PostMapping("/v1/completions")
    public CompletionResponse complete(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody CompletionRequest request) {

        // Stubbed response — see class javadoc. The rate limiting above this line
        // is the actual subject of the demo.
        String stubbedCompletion = "This is a stubbed completion for prompt: \"" + request.prompt() + "\"";
        return new CompletionResponse(stubbedCompletion, apiKey);
    }
}
