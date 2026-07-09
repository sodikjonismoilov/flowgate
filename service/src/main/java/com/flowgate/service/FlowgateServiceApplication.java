package com.flowgate.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Flowgate demo service: a multi-tenant AI  API  gateway.
 *
 * <p>Exposes {@code POST /v1/completions}, protected by {@code @RateLimit}.
 * Each caller authenticates with an {@code X-API-Key} header, and each distinct
 * key gets an independent, Redis-backed rate limit-demonstrating the core
 * distributed-systems property flowgate was built to solve: per-key isolation
 * that holds even across multiple instances of this service.
 *
 * <p>See {@link com.flowgate.service.controller.LlmGatewayController} for the
 *  * endpoint and {@code @RateLimit} configuration, and
 *  * {@link com.flowgate.service.exception.GlobalExceptionHandler} for how a
 *  * rejected request becomes an HTTP 429.
 *
 *  <p>Requires a running Redis instance (see {@code application.yml} for
 *  connection settings - defaults to {@code localhost:6379}
 */
@SpringBootApplication
public class FlowgateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowgateServiceApplication.class, args);
    }
}
