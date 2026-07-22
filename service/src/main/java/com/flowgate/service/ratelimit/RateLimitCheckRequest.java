package com.flowgate.service.ratelimit;

import com.flowgate.core.Algorithm;

import java.time.Duration;

/**
 * Protocol-agnostic rate-limit check request.
 *
 * <p>Deliberately independent of both the generated gRPC {@code CheckRequest}
 * and any REST DTO — this is the shape {@link RateLimitCheckService} actually
 * operates on. Each transport adapter (gRPC, REST) decodes its own wire format
 * into this record; the enforcement logic downstream never needs to know
 * which transport a request arrived over.
 */
public record RateLimitCheckRequest(
        String key,
        Algorithm algorithm,
        int limit,
        Duration window
) {
}
