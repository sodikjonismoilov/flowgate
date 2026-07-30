package com.flowgate.service.controller;

import com.flowgate.core.Algorithm;
import com.flowgate.core.model.RateLimitResult;
import com.flowgate.service.ratelimit.RateLimitCheckRequest;
import com.flowgate.service.ratelimit.RateLimitCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * REST fallback for clients that can't speak gRPC. Same enforcement path as
 * {@link com.flowgate.service.grpc.GrpcRateLimitService} — both decode their
 * wire format into {@link RateLimitCheckRequest} and delegate to the shared
 * {@link RateLimitCheckService}.
 */
@RestController
public class RateLimitCheckController {

    private final RateLimitCheckService checkService;

    public RateLimitCheckController(RateLimitCheckService checkService) {
        this.checkService = checkService;
    }

    @PostMapping("/check")
    public ResponseEntity<CheckResponseDto> check(@RequestBody CheckRequestDto request) {
        RateLimitCheckRequest domainRequest = new RateLimitCheckRequest(
                request.tenantId(),
                request.key(),
                request.algorithm(),
                request.limit(),
                Duration.ofMillis(request.windowMillis())
        );

        RateLimitResult result = checkService.check(domainRequest);

        CheckResponseDto response = new CheckResponseDto(
                result.allowed(),
                result.remaining(),
                result.retryAfter().toMillis()
        );

        return ResponseEntity.ok(response);
    }

    /** Mirrors the proto CheckRequest shape; Algorithm's JSON form is its enum name (e.g. "TOKEN_BUCKET"). */
    public record CheckRequestDto(String tenantId,String key, Algorithm algorithm, int limit, long windowMillis) {
    }

    public record CheckResponseDto(boolean allowed, long remaining, long retryAfterMillis) {
    }
}