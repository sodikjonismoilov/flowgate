package com.flowgate.core.inmemory;

import com.flowgate.core.Algorithm;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class SlidingWindowLogRateLimiterTest {

    private final RateLimiterConfig config = RateLimiterConfig.slidingWindow(
            Algorithm.SLIDING_WINDOW_LOG, 10, Duration.ofMinutes(1));

    @Test
    void shouldAllowRequestWhenUnderLimit() {
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(config);

        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.remaining()).isEqualTo(9);
    }

    @Test
    void shouldRejectRequestWhenAtLimit() {
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(config);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("user:1");
        }

        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isEqualTo(0);
        assertThat(result.retryAfter().toNanos()).isPositive();
    }

    @Test
    void shouldExpireOldEntriesAndAllowAgain() {
        long[] fakeTime = {0L};
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(config, () -> fakeTime[0]);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("user:1");
        }

        fakeTime[0] += Duration.ofMinutes(1).toNanos() + 1;

        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(9);
    }
}