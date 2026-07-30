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

@GrpcService
public class GrpcRateLimitService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GrpcRateLimitService.class);

    private final RateLimitCheckService checkService;

    public GrpcRateLimitService(RateLimitCheckService checkService) {
        this.checkService = checkService;
    }

    @Override
    public void checkRateLimit(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        try {
            RateLimitCheckRequest domainRequest = new RateLimitCheckRequest(
                    request.getTenantId(),
                    request.getKey(),
                    mapAlgorithm(request.getAlgorithm()),
                    toIntLimit(request.getLimit()),
                    Duration.ofMillis(request.getWindowMillis())
            );

            RateLimitResult result = checkService.check(domainRequest);

            log.debug("gRPC rate limit check: tenant={}, key={}, algorithm={}, allowed={}, remaining={}",
                    request.getTenantId(), request.getKey(), request.getAlgorithm(), result.allowed(), result.remaining());

            CheckResponse response = CheckResponse.newBuilder()
                    .setAllowed(result.allowed())
                    .setRemaining(result.remaining())
                    .setRetryAfterMillis(result.retryAfter().toMillis())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }

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

    private int toIntLimit(long limit) {
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit must be a positive int, got: " + limit);
        }
        return (int) limit;
    }
}