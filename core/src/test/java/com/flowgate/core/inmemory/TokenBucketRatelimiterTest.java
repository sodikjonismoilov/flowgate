package com.flowgate.core.inmemory;

import com.flowgate.core.Algorithm;
import com.flowgate.core.RateLimiterConfig;
import com.flowgate.core.model.RateLimitResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;

 class TokenBucketRateLimiterTest {

    //shared config: 10 requests per minute, burst of 10
    private final RateLimiterConfig config = RateLimiterConfig.tokenBucket(10, Duration.ofMinutes(1));
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);



    @Test
    void shouldAllowRequestWhenBucketHasTokens() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.remaining()).isEqualTo(19);

    }

    @Test
    void shouldRejectRequestWhenBucketIsEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
        // drain all 10 tokens
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("user:1");
        }

        //11th request should be rejected
        RateLimitResult result = limiter.tryAcquire("user:1");

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isEqualTo(0);
    }

     @Test
     void shouldRefillTokensOverTime() {
         long[] fakeTime = { 0L };
         TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config, () -> fakeTime[0]);

         // drain the bucket (burst capacity is 20, so we need 20 requests)
         for (int i = 0; i < 20; i++) {
             limiter.tryAcquire("user:1");
         }

        // advance time by 1 full minute
        fakeTime[0] += Duration.ofMinutes(1).toNanos();

        // now what should happen?
        RateLimitResult result = limiter.tryAcquire("user:1");

        // write the assertions
        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.remaining()).isEqualTo(9);

    }

}
