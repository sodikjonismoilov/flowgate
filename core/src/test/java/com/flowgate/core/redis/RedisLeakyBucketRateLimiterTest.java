package com.flowgate.core.redis;

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

/**
 * Integration test for {@link RedisLeakyBucketRateLimiter} against a real Redis instance.
 *
 * <p>Mirrors {@link RedisTokenBucketRateLimiterTest} in structure — same Testcontainers
 * setup, same fake-clock approach for deterministic time-based assertions. The difference
 * is in what's being asserted: instead of a bucket refilling up toward a cap, the bucket
 * here drains down toward zero, and requests are rejected when they'd overflow capacity
 * rather than when there's nothing left to consume.
 */
@Testcontainers
class RedisLeakyBucketRateLimiterTest {

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
    void allowsRequestsUpToCapacity() {
        // limit = 10 per minute → capacity = 10 (leaky bucket: burstCapacity == limit,
        // and the algorithm uses config.limit() as the capacity bound directly).
        RateLimiterConfig config = RateLimiterConfig.leakyBucket(10, Duration.ofMinutes(1));
        RedisLeakyBucketRateLimiter limiter = new RedisLeakyBucketRateLimiter(config, redisClient, clock);

        String key = "test:capacity:" + System.nanoTime();

        // Each request adds 1 to the level. Level goes 1, 2, ... 10 — all within capacity.
        for (int i = 0; i < 10; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 10 should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void rejectsRequestOnceCapacityIsExhausted() {
        RateLimiterConfig config = RateLimiterConfig.leakyBucket(10, Duration.ofMinutes(1));
        RedisLeakyBucketRateLimiter limiter = new RedisLeakyBucketRateLimiter(config, redisClient, clock);

        String key = "test:exhaust:" + System.nanoTime();

        // Fill the bucket to capacity (level == 10).
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(key);
        }

        // The 11th request would push level to 11 > capacity 10 — overflow, rejected.
        RateLimitResult result = limiter.tryAcquire(key);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isPositive();
    }

    @Test
    void drainsOverTimeAccordingToLeakRate() {
        // limit = 60 per minute → 1 unit drains per second → 1 unit per 1000ms.
        // capacity = 60 (== limit for leaky bucket).
        RateLimiterConfig config = RateLimiterConfig.leakyBucket(60, Duration.ofMinutes(1));
        RedisLeakyBucketRateLimiter limiter = new RedisLeakyBucketRateLimiter(config, redisClient, clock);

        String key = "test:drain:" + System.nanoTime();

        // Fill the bucket to capacity (level == 60).
        for (int i = 0; i < 60; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 60 should be allowed", i + 1)
                    .isTrue();
        }

        // Bucket is full: the next request would overflow, rejected.
        RateLimitResult rejected = limiter.tryAcquire(key);
        assertThat(rejected.allowed()).isFalse();

        // Advance the fake clock by exactly one leak interval (1000ms = 1 unit drained).
        // Level drops from 60 to 59, leaving room for exactly 1 more.
        fakeNow.addAndGet(1000L);

        RateLimitResult afterDrain = limiter.tryAcquire(key);
        assertThat(afterDrain.allowed()).isTrue();
        assertThat(afterDrain.remaining()).isEqualTo(0);

        // Level is back at 60 with no time passed — immediately rejected again.
        RateLimitResult rejectedAgain = limiter.tryAcquire(key);
        assertThat(rejectedAgain.allowed()).isFalse();
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        RateLimiterConfig config = RateLimiterConfig.leakyBucket(5, Duration.ofMinutes(1));
        RedisLeakyBucketRateLimiter limiter = new RedisLeakyBucketRateLimiter(config, redisClient, clock);

        String keyA = "test:independent:a:" + System.nanoTime();
        String keyB = "test:independent:b:" + System.nanoTime();

        // Fill keyA's bucket to capacity.
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(keyA);
        }
        RateLimitResult keyARejected = limiter.tryAcquire(keyA);
        assertThat(keyARejected.allowed()).isFalse();

        // keyB has never been touched — its bucket should still be empty (plenty of room).
        RateLimitResult keyBAllowed = limiter.tryAcquire(keyB);
        assertThat(keyBAllowed.allowed()).isTrue();
    }

    @Test
    void multiTokenRequestConsumesMultipleUnitsAtOnce() {
        RateLimiterConfig config = RateLimiterConfig.leakyBucket(10, Duration.ofMinutes(1));
        RedisLeakyBucketRateLimiter limiter = new RedisLeakyBucketRateLimiter(config, redisClient, clock);

        String key = "test:multitoken:" + System.nanoTime();

        // Add 7 units in one request (e.g. a "cost 7" operation). Level: 0 -> 7.
        RateLimitResult first = limiter.tryAcquire(key, 7);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(3);

        // Level is 7, capacity 10 — adding 5 would overflow to 12. Rejected.
        RateLimitResult second = limiter.tryAcquire(key, 5);
        assertThat(second.allowed()).isFalse();

        // But adding 3 fits exactly: level 7 -> 10.
        RateLimitResult third = limiter.tryAcquire(key, 3);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isEqualTo(0);
    }
}