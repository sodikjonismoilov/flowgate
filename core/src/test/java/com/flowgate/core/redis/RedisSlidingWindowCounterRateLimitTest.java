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
class RedisSlidingWindowCounterRateLimiterTest {

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
    static void tearDownClient() {
        redisClient.shutdown();
    }

    private final AtomicLong fakeNow = new AtomicLong(0L);
    private final MillisClock clock = fakeNow::get;

    @BeforeEach
    void resetClock() {
        fakeNow.set(0L);
    }

    @Test
    void allowsRequestsUpToLimit() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_COUNTER, 10, Duration.ofMinutes(1));
        RedisSlidingWindowCounterRateLimiter limiter =
                new RedisSlidingWindowCounterRateLimiter(config, redisClient, clock);

        String key = "test:limit:" + System.nanoTime();

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
                Algorithm.SLIDING_WINDOW_COUNTER, 10, Duration.ofMinutes(1));
        RedisSlidingWindowCounterRateLimiter limiter =
                new RedisSlidingWindowCounterRateLimiter(config, redisClient, clock);

        String key = "test:reject:" + System.nanoTime();

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(key);
        }

        RateLimitResult result = limiter.tryAcquire(key);
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isPositive();
    }

    @Test
    void weightsCountFromPreviousWindowCorrectly() {
        // This is the key correctness test for the sliding window counter.
        //
        // Setup: limit=100, window=60000ms (1 minute).
        // Step 1 (T=0):       Make 80 requests → curr_count=80, prev_count=0.
        // Step 2 (T=60000):   Window slides    → prev_count=80, curr_count=0.
        // Step 3 (T=90000):   50% through new window.
        //   elapsed_fraction = (90000-60000)/60000 = 0.5
        //   weighted_prev    = floor(80 * 0.5) = 40
        //   estimated        = 0 + 40 = 40
        //   remaining        = 100 - 40 = 60
        // So exactly 60 more requests should be allowed, and the 61st rejected.
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_COUNTER, 100, Duration.ofMinutes(1));
        RedisSlidingWindowCounterRateLimiter limiter =
                new RedisSlidingWindowCounterRateLimiter(config, redisClient, clock);

        String key = "test:blend:" + System.nanoTime();

        // Step 1: fill 80 requests at T=0.
        for (int i = 0; i < 80; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 80 at T=0 should be allowed", i + 1)
                    .isTrue();
        }

        // Step 2 + 3: advance clock to 50% through the next window.
        fakeNow.set(90_000L);

        // 60 requests should be allowed (limit 100 - weighted_prev 40 = 60 remaining).
        for (int i = 0; i < 60; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 60 at T=90000 should be allowed", i + 1)
                    .isTrue();
        }

        // The 61st exceeds the weighted estimate (40 from prev + 60 from curr = 100, +1 = 101).
        RateLimitResult rejected = limiter.tryAcquire(key);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isPositive();
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_COUNTER, 5, Duration.ofMinutes(1));
        RedisSlidingWindowCounterRateLimiter limiter =
                new RedisSlidingWindowCounterRateLimiter(config, redisClient, clock);

        String keyA = "test:independent:a:" + System.nanoTime();
        String keyB = "test:independent:b:" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(keyA);
        }
        assertThat(limiter.tryAcquire(keyA).allowed()).isFalse();
        assertThat(limiter.tryAcquire(keyB).allowed()).isTrue();
    }

    @Test
    void multiTokenRequestConsumesMultipleUnitsAtOnce() {
        RateLimiterConfig config = RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_COUNTER, 10, Duration.ofMinutes(1));
        RedisSlidingWindowCounterRateLimiter limiter =
                new RedisSlidingWindowCounterRateLimiter(config, redisClient, clock);

        String key = "test:multitoken:" + System.nanoTime();

        RateLimitResult first = limiter.tryAcquire(key, 7);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(3);

        assertThat(limiter.tryAcquire(key, 4).allowed()).isFalse();

        RateLimitResult third = limiter.tryAcquire(key, 3);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isEqualTo(0);
    }
}