package com.flowgate.library.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Spring Boot auto-configuration for the Flowgate library.
 *
 * <p>How auto-configuration works:
 * When Spring Boot starts, it reads the file at:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and registers every class listed there as a configuration class. This class
 * is listed there, so Spring Boot picks it up automatically when the JAR is
 * on the classpath — no manual {@code @Import} or {@code @EnableFlowgate} needed.
 *
 * <p>{@code @ConditionalOnClass} means: only activate this config if Lettuce
 * (io.lettuce.core.RedisClient) is on the classpath. This prevents Flowgate from
 * activating in apps that somehow include the library without Redis support.
 *
 * <p><b>Beans registered here (Week 4):</b>
 * <ul>
 *   <li>One {@link com.flowgate.core.RateLimiter} per Algorithm, backed by Redis</li>
 *   <li>{@link com.flowgate.library.aspect.RateLimitAspect} — the AOP interceptor</li>
 * </ul>
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class FlowgateAutoConfiguration {
    // Beans will be registered here in Week 4
    // when the RateLimiter implementations and AOP aspect are wired up.
}
