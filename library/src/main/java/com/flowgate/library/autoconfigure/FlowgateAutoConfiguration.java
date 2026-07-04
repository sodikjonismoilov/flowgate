package com.flowgate.library.autoconfigure;

import com.flowgate.library.aspect.RateLimitAspect;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Spring Boot auto-configuration for the Flowgate rate limiting library.
 *
 * <h3>How Spring Boot discovers this class</h3>
 * Spring Boot 3.x reads:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * Any project with {@code flowgate-library} on its classpath gets rate limiting
 * configured automatically — no {@code @Import} or {@code @EnableFlowgate} needed.
 *
 * <h3>Beans registered</h3>
 * <ul>
 *   <li>{@code RedisClient} — Lettuce client pointed at spring.data.redis.host/port.
 *       Skipped if the consuming app already defines its own {@code RedisClient} bean.</li>
 *   <li>{@code RateLimitAspect} — AOP interceptor for {@code @RateLimit} annotations.</li>
 * </ul>
 *
 * <h3>@EnableAspectJAutoProxy</h3>
 * Tells Spring to create AOP proxies for beans annotated with @Aspect.
 * Without this, {@link RateLimitAspect} would be a Spring bean but its
 * advice would never fire.
 *
 * <h3>@ConditionalOnClass</h3>
 * Only activates if Lettuce is on the classpath, preventing activation
 * in environments without Redis support.
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class FlowgateAutoConfiguration {

    /**
     * Lettuce client used by all Redis-backed rate limiters.
     *
     * <p>Reads {@code spring.data.redis.host} and {@code spring.data.redis.port}
     * (defaulting to localhost:6379) so consuming apps configure Redis the
     * same way they would for Spring Data Redis.
     *
     * <p>{@code @ConditionalOnMissingBean}: if the consuming app already provides
     * a {@code RedisClient} bean, we use that one instead of creating a second
     * connection to Redis.
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisClient flowgateRedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    /**
     * The AOP aspect that intercepts {@code @RateLimit}-annotated methods.
     * Injected with the {@code RedisClient} bean (ours or the app's own).
     */
    @Bean
    public RateLimitAspect rateLimitAspect(RedisClient flowgateRedisClient) {
        return new RateLimitAspect(flowgateRedisClient);
    }
}