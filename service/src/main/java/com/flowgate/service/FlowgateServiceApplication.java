package com.flowgate.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Flowgate standalone service.
 *
 * <p>Exposes rate limiting as a language-agnostic network service.
 * Clients POST to /check with a key, algorithm, limit, and window and receive
 * an allow/deny decision with remaining quota and retry-after duration.
 *
 * <p>Build milestones:
 * <ul>
 *   <li>Week 1: Basic Spring Boot setup, health check at /actuator/health
 *   <li>Week 3: Redis integration, first real POST /check endpoint
 *   <li>Week 5: gRPC endpoint alongside REST, JWT auth
 *   <li>Week 7: Prometheus metrics, structured logging, Grafana dashboard
 * </ul>
 */
@SpringBootApplication
public class FlowgateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowgateServiceApplication.class, args);
    }
}
