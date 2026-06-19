package com.flowgate.core.redis;


import com.flowgate.core.Algorithm;
import com.flowgate.core.MillisClock;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


@Testcontainers
public class RedisSlidingWindowLogRateLimiterTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedisClient redisClient;

    @BeforeAll
    static void setUpClient() {
        String uri = "redis://" + redisContainer.getHost() + ":" + redisContainer.getFirstMappedPort();
        redisClient = RedisClient.create(uri);
    }

    @AfterAll
    static void tearDownClient() { redisClient.shutdown(); }

    private final AtomicLong fakeNow = new AtomicLong(0L);
    private final MillisClock clock = fakeNow::get;

    @BeforeEach
    void resetClock() { fakeNow.set(0L); }

    @Test
    void allowsRequestsUpToLimit() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, 10, Duration.ofMinutes(1));
        RedisSlidingWindowLogRateLimiter limiter =
                new RedisSlidingWindowLogRateLimiter(config, redisClient, clock);

        String key = "test:limit" + System.nanoTime();

        for (int i = 0; i < 10; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 10 should be allowed", i + 1)
                    .isTrue();
        }

    }
    @Test
    void rejectsOnceAtLimit() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, 10, Duration.ofMinutes(1));
        RedisSlidingWindowLogRateLimiter limiter =
                new RedisSlidingWindowLogRateLimiter(config, redisClient, clock);

        String key = "test:reject:" + System.nanoTime();

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(key);
        }

        RateLimitResult result = limiter.tryAcquire(key);
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isPositive();
    }

    @Test
    void expiresOldEntriesOncePastWindow() {
        // Fill the window completely, advance past the window, verify slots are free again.
        // This is the core correctness test for the sliding window log — old timestamps
        // must be evicted by ZREMRANGEBYSCORE so they don't count against future requests.
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofMinutes(1));
        RedisSlidingWindowLogRateLimiter limiter =
                new RedisSlidingWindowLogRateLimiter(config, redisClient, clock);

        String key = "test:expiry:" + System.nanoTime();

        // T=0: fill to limit.
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(key);
        }
        assertThat(limiter.tryAcquire(key).allowed()).isFalse();

        // Advance the clock just past one full window (60001ms > 60000ms).
        // All entries at T=0 have score 0; cutoff = 60001 - 60000 = 1.
        // ZREMRANGEBYSCORE removes scores in [-inf, 1], so score 0 is evicted.
        fakeNow.set(60_001L);

        // All 5 slots should be free again.
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d after window reset should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofMinutes(1));
        RedisSlidingWindowLogRateLimiter limiter =
                new RedisSlidingWindowLogRateLimiter(config, redisClient, clock);

        String keyA = "test:independent:a:" + System.nanoTime();
        String keyB = "test:independent:b:" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(keyA);
        }
        assertThat(limiter.tryAcquire(keyA).allowed()).isFalse();

        // keyB has its own sorted set — completely unaffected by keyA.
        assertThat(limiter.tryAcquire(keyB).allowed()).isTrue();
    }

    @Test
    void multiTokenRequestConsumesMultipleSlotsAtOnce() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, 10, Duration.ofMinutes(1));
        RedisSlidingWindowLogRateLimiter limiter =
                new RedisSlidingWindowLogRateLimiter(config, redisClient, clock);

        String key = "test:multitoken:" + System.nanoTime();

        // Consume 7 slots in one call.
        RateLimitResult first = limiter.tryAcquire(key, 7);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(3);

        // 3 slots remain — requesting 4 should be rejected.
        assertThat(limiter.tryAcquire(key, 4).allowed()).isFalse();

        // But requesting 3 fits exactly.
        RateLimitResult third = limiter.tryAcquire(key, 3);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isEqualTo(0);
    }
}




