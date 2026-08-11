package com.flowgate.service.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Ensures every REST request carries a correlation ID, either propagated
 * from an incoming {@code X-Correlation-Id} header (e.g. from an upstream
 * gateway) or freshly generated. The ID is placed in SLF4J's MDC so every log
 * line emitted while handling this request automatically includes it,
 * and echoes back on the response so callers can correlate their own logs.
 *
 *<p>{@code @Order(HIGHEST_PRECEDENCE}-adjacent placement matters: this must run
 * before any application logging happens, so it's registered as a plan servlet
 * {@code Filter}, which Spring Boot auto-registers ahead of
 * {@code DispatcherServlet}.</p>
 */
@Component
@Order(1)
public class CorrelationIdFilter implements jakarta.servlet.Filter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String correlationId = httpRequest.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }

        httpResponse.setHeader(HEADER_NAME, correlationId);

        try {
            MDC.put(MDC_KEY, correlationId);
            chain.doFilter(request, response);
        } finally {
            //always clear MDC, even on exception = otherwise the next
            //request handled by this pooled thread inherits a stale ID.
            MDC.remove(MDC_KEY);
        }
    }
}
