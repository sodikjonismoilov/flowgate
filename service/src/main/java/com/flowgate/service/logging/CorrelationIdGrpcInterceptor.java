package com.flowgate.service.logging;

import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * gRPC equivalent of {@link CorrelationIdFilter}. Reads an incoming
 * {@code correlation-id} metadata entry (gRPC's analog of an HTTP header) or
 * generates one, then ensures MDC carries it for every callback of this call.
 *
 * <p>Unlike a servlet filter — where one thread handles the whole request —
 * gRPC may invoke {@code onMessage}/{@code onHalfClose}/{@code onComplete} on
 * different pooled threads. MDC is thread-local, so it must be set and
 * cleared around each individual callback rather than once at call start.
 *
 * <p>{@code @GrpcGlobalServerInterceptor} registers this against every gRPC
 * service automatically, mirroring {@code RateLimitCheckService} being the
 * single shared enforcement path for both transports.
 */
@GrpcGlobalServerInterceptor
public class CorrelationIdGrpcInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> CORRELATION_ID_METADATA_KEY =
            Metadata.Key.of("correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String correlationId = headers.get(CORRELATION_ID_METADATA_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        final String finalCorrelationId = correlationId;

        ServerCall.Listener<ReqT> delegate = withMdc(finalCorrelationId,
                () -> next.startCall(call, headers));

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                runWithMdc(finalCorrelationId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                runWithMdc(finalCorrelationId, super::onHalfClose);
            }

            @Override
            public void onComplete() {
                runWithMdc(finalCorrelationId, super::onComplete);
            }

            @Override
            public void onCancel() {
                runWithMdc(finalCorrelationId, super::onCancel);
            }
        };
    }

    private <ReqT> ServerCall.Listener<ReqT> withMdc(String correlationId,
                                                     java.util.function.Supplier<ServerCall.Listener<ReqT>> supplier) {
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            return supplier.get();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void runWithMdc(String correlationId, Runnable runnable) {
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            runnable.run();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}