package com.flowgate.service.grpc;

import com.flowgate.core.model.RateLimitResult;
import com.flowgate.service.grpc.proto.CheckRequest;
import com.flowgate.service.grpc.proto.CheckResponse;
import com.flowgate.service.grpc.proto.RateLimitServiceGrpc;
import com.flowgate.service.ratelimit.RateLimitCheckRequest;
import com.flowgate.service.ratelimit.RateLimitCheckService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * gRPC entry point for flowgate's "rate limiting as a service" API.
 *
 * <p>Purely a transport adapter: decodes the wire-level {@link CheckRequest}
 * into a protocol-agnostic {@link RateLimitCheckRequest} and delegates all
 * enforcement logic — limiter caching, algorithm dispatch, validation — to
 * {@link RateLimitCheckService}, which is shared with the REST fallback.
 */
@GrpcService
public class GrpcRateLimitService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GrpcRateLimitService.class);

    private final RateLimitCheckService checkService;

    public GrpcRateLimitService(RateLimitCheckService checkService) {
        this.checkService = checkService;
    }

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        try {
            RateLimitCheckRequest domainRequest = new RateLimitCheckRequest(
                    request.getKey(),
                    mapAlgorithm(request.getAlgorithm()),
                    toIntLimit(request.getLimit()),
                    Duration.ofMillis(request.getWindowMillis())
            );

            RateLimitResult result = checkService.check(domainRequest);

            log.debug("gRPC rate limit check: key={}, algorithm={}, allowed={}, remaining={}",
                    request.getKey(), request.getAlgorithm(), result.allowed(), result.remaining());

            CheckResponse response = CheckResponse.newBuilder()
                    .setAllowed(result.allowed())
                    .setRemaining(result.remaining())
                    .setRetryAfterMillis(result.retryAfter().toMillis())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            // gRPC has no automatic exception→status mapping (unlike Spring's
            // @ExceptionHandler → HTTP code), so client errors are mapped explicitly.
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }

    /**
     * Maps the wire-level proto enum to flowgate-core's Algorithm enum.
     * ALGORITHM_UNSPECIFIED (the proto zero-value) and UNRECOGNIZED (proto3's
     * forward-compat catch-all) are rejected explicitly rather than falling
     * through to a default algorithm.
     */
    private com.flowgate.core.Algorithm mapAlgorithm(com.flowgate.service.grpc.proto.Algorithm protoAlgorithm) {
        return switch (protoAlgorithm) {
            case TOKEN_BUCKET -> com.flowgate.core.Algorithm.TOKEN_BUCKET;
            case LEAKY_BUCKET -> com.flowgate.core.Algorithm.LEAKY_BUCKET;
            case SLIDING_WINDOW_LOG -> com.flowgate.core.Algorithm.SLIDING_WINDOW_LOG;
            case SLIDING_WINDOW_COUNTER -> com.flowgate.core.Algorithm.SLIDING_WINDOW_COUNTER;
            case ALGORITHM_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("algorithm must be set on CheckRequest");
        };
    }

    /** proto3 uses int64 for limit; flowgate-core's config takes an int. */
    private int toIntLimit(long limit) {
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit must be a positive int, got: " + limit);
        }
        return (int) limit;
    }
}