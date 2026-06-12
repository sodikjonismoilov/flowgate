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
 * Integration test for {@link RedisTokenBucketRateLimiter} against a real Redis instance.
 *
 * <h3>Why Testcontainers instead of a mock?</h3>
 * The whole point of this class is the Lua script — atomicity, HMGET/HMSET semantics,
 * PEXPIRE behaviour. A mocked Redis client would only verify that we *called* the right
 * methods with the right arguments, not that the script actually does the right thing
 * when Redis evaluates it. Testcontainers spins up a real Redis in Docker, so the Lua
 * script runs on real Redis — same as production.
 *
 * <h3>Why a fake MillisClock?</h3>
 * Refill is time-based: "X tokens per millisecond". Testing that with real time would mean
 * either waiting seconds per test (slow) or using tiny windows that make timing assertions
 * flaky. Instead, each test controls an {@link AtomicLong} that the limiter reads as "now" —
 * advancing it instantly simulates the passage of time.
 */
@Testcontainers
class RedisTokenBucketRateLimiterTest {

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

    /**
     * Each test gets a fresh key namespace by using a unique key string,
     * since flushing the whole Redis instance between tests would slow things down
     * and isn't necessary — keys are independent by design.
     */
    private final AtomicLong fakeNow = new AtomicLong(0L);
    private final MillisClock clock = fakeNow::get;

    @BeforeEach
    void resetClock() {
        fakeNow.set(0L);
    }

    @Test
    void allowsRequestsUpToBurstCapacity() {
        // limit = 10 per minute, burstCapacity = 10 (default = 2x limit would be 20,
        // but we set it explicitly so the math in this test is easy to follow)
        RateLimiterConfig config = RateLimiterConfig.tokenBucket(10, Duration.ofMinutes(1), 10);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(config, redisClient, clock);

        String key = "test:burst:" + System.nanoTime();

        for (int i = 0; i < 10; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 10 should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void rejectsRequestOnceBurstCapacityIsExhausted() {
        RateLimiterConfig config = RateLimiterConfig.tokenBucket(10, Duration.ofMinutes(1), 10);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(config, redisClient, clock);

        String key = "test:exhaust:" + System.nanoTime();

        // Drain the bucket completely.
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(key);
        }

        // The 11th request has no tokens left.
        RateLimitResult result = limiter.tryAcquire(key);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isPositive();
    }

    @Test
    void refillsTokensOverTimeAccordingToRefillRate() {
        // limit = 60 per minute → 1 token per second → 1 token per 1000ms.
        // burstCapacity == limit (no extra burst headroom) keeps the refill math
        // easy to follow: drain all 60, then watch exactly 1 token come back.
        RateLimiterConfig config = RateLimiterConfig.tokenBucket(60, Duration.ofMinutes(1), 60);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(config, redisClient, clock);

        String key = "test:refill:" + System.nanoTime();

        // Drain the bucket completely (60 tokens).
        for (int i = 0; i < 60; i++) {
            RateLimitResult result = limiter.tryAcquire(key);
            assertThat(result.allowed())
                    .as("request %d of 60 should be allowed", i + 1)
                    .isTrue();
        }

        // Bucket is empty: next request is rejected.
        RateLimitResult rejected = limiter.tryAcquire(key);
        assertThat(rejected.allowed()).isFalse();

        // Advance the fake clock by exactly one refill interval (1000ms = 1 token).
        fakeNow.addAndGet(1000L);

        // Bucket should now have refilled to exactly 1 token — request is allowed again.
        RateLimitResult afterRefill = limiter.tryAcquire(key);
        assertThat(afterRefill.allowed()).isTrue();
        assertThat(afterRefill.remaining()).isEqualTo(0);

        // The 1 token was just consumed again — immediately rejected with no time passed.
        RateLimitResult rejectedAgain = limiter.tryAcquire(key);
        assertThat(rejectedAgain.allowed()).isFalse();
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        RateLimiterConfig config = RateLimiterConfig.tokenBucket(5, Duration.ofMinutes(1), 5);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(config, redisClient, clock);

        String keyA = "test:independent:a:" + System.nanoTime();
        String keyB = "test:independent:b:" + System.nanoTime();

        // Exhaust keyA's bucket entirely.
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire(keyA);
        }
        RateLimitResult keyARejected = limiter.tryAcquire(keyA);
        assertThat(keyARejected.allowed()).isFalse();

        // keyB has never been touched — its bucket should still be full.
        RateLimitResult keyBAllowed = limiter.tryAcquire(keyB);
        assertThat(keyBAllowed.allowed()).isTrue();
    }

    @Test
    void multiTokenRequestConsumesMultipleUnitsAtOnce() {
        RateLimiterConfig config = RateLimiterConfig.tokenBucket(10, Duration.ofMinutes(1), 10);
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(config, redisClient, clock);

        String key = "test:multitoken:" + System.nanoTime();

        // Consume 7 tokens in one request (e.g. a "cost 7" operation).
        RateLimitResult first = limiter.tryAcquire(key, 7);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(3);

        // 3 tokens remain — requesting 5 should be rejected.
        RateLimitResult second = limiter.tryAcquire(key, 5);
        assertThat(second.allowed()).isFalse();

        // But requesting 3 should still succeed.
        RateLimitResult third = limiter.tryAcquire(key, 3);
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isEqualTo(0);
    }
}