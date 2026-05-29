package com.flowgate.library.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Spring Boot auto-configuration entry point for the flowgate library.
 *
 * <h3>How Spring Boot discovers this class</h3>
 * Spring Boot scans the file:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * This is the Spring Boot 3.x mechanism (replaces the old spring.factories).
 * Any project with {@code flowgate-library} on its classpath gets rate limiting
 * configured automatically — no @Import or @EnableFlowgate annotation needed.
 *
 * <h3>@ConditionalOnClass</h3>
 * Only activates if Lettuce is on the classpath. This prevents the library
 * from trying to create Redis-backed beans in apps that somehow include the
 * JAR without a Redis client — a defensive guard against misconfigured deployments.
 *
 * <h3>@EnableAspectJAutoProxy</h3>
 * Tells Spring to create AOP proxies for beans annotated with @Aspect.
 * Without this, the {@link com.flowgate.library.aspect.RateLimitAspect} bean
 * would exist in the context but its advice would never fire.
 *
 * <h3>Beans to register here (Week 4)</h3>
 * <ul>
 *   <li>{@code RateLimiterFactory} — creates the right implementation based
 *       on the @RateLimit annotation's algorithm attribute
 *   <li>{@code RateLimitAspect} — AOP advice that intercepts annotated methods,
 *       invokes the factory, and throws RateLimitExceededException on denial
 *   <li>{@code FlowgateProperties} — @ConfigurationProperties("flowgate") for
 *       default algorithm, Redis key namespace, and fail-open/closed behavior
 * </ul>
 *
 * TODO (Week 4): register the beans above
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class FlowgateAutoConfiguration {
    // Week 4: register RateLimiterFactory, RateLimitAspect, FlowgateProperties
}
