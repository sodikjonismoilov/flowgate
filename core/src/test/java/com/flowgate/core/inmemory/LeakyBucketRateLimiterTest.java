package com.flowgate.core.inmemory;


import com.flowgate.core.Algorithm;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;

public class LeakyBucketRateLimiterTest {

    private final RateLimiterConfig config = RateLimiterConfig.leakyBucket(10, Duration.ofMinutes(1));
    private final LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(config);

    @Test
    void shouldAllowRequestWhenBucketNotFull() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(config);

        RateLimitResult result = limiter.tryAcquire("user:1");
        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
    }
    @Test
    void shouldRejectRequestWhenBucketFull() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(config);

        //fill the bucket to capacity
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("user:1");
        }

        //11th request - bucket is full
        RateLimitResult result = limiter.tryAcquire("user:1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isEqualTo(0);

    }
    @Test
    void shouldRefillBucketOverTime() {
        long [] fakeTime = {0L};
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(config, () -> fakeTime[0]);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("user:1");
        }
        fakeTime[0] += Duration.ofMinutes(1).toNanos();
        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
    }

}
