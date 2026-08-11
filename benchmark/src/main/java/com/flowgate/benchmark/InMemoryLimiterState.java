package com.flowgate.benchmark;

import com.flowgate.core.RateLimiter;
import com.flowgate.core.inmemory.LeakyBucketRateLimiter;
import com.flowgate.core.inmemory.SlidingWindowCounterRateLimiter;
import com.flowgate.core.inmemory.SlidingWindowLogRateLimiter;
import com.flowgate.core.inmemory.TokenBucketRateLimiter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Holds the four in-memory limiters and the rotating key pool.
 *
 * <p>{@link Scope#Benchmark} so a single instance is shared by every benchmark
 * thread — that is the production shape (one limiter bean per config, shared across
 * request threads) and it means contention on the shared {@code ConcurrentHashMap}
 * is part of what gets measured rather than being hidden by per-thread copies.
 *
 * <p>Limiters are rebuilt at {@link Level#Iteration} rather than once per trial so
 * each measurement iteration starts from an empty map. Without this, the sliding
 * window log's deques would carry state across iterations and later iterations
 * would look slower than earlier ones for reasons unrelated to the algorithm.
 */
@State(Scope.Benchmark)
public class InMemoryLimiterState {

    public RateLimiter tokenBucket;
    public RateLimiter leakyBucket;
    public RateLimiter slidingWindowLog;
    public RateLimiter slidingWindowCounter;

    private String[] keys;

    /**
     * Round-robin cursor over the key pool. Deliberately a plain {@code int}: making it
     * atomic would inject a contended CAS into every measured operation and would
     * distort the very numbers we are trying to read. A benign race here only means two
     * threads occasionally pick the same key, which is exactly what real traffic does.
     */
    private int cursor;

    @Setup(Level.Iteration)
    public void setup() {
        tokenBucket = new TokenBucketRateLimiter(BenchmarkConfigs.tokenBucket());
        leakyBucket = new LeakyBucketRateLimiter(BenchmarkConfigs.leakyBucket());
        slidingWindowLog = new SlidingWindowLogRateLimiter(BenchmarkConfigs.slidingWindowLog());
        slidingWindowCounter = new SlidingWindowCounterRateLimiter(BenchmarkConfigs.slidingWindowCounter());
        keys = BenchmarkConfigs.keyPool("mem");
        cursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        // Drop references so the maps (especially the log's deques) are collectable
        // before the next iteration allocates fresh ones.
        tokenBucket = null;
        leakyBucket = null;
        slidingWindowLog = null;
        slidingWindowCounter = null;
    }

    /** Next key in the rotation. Masking is cheap and branch-free; pool size is a power of two. */
    public String nextKey() {
        return keys[(cursor++) & (BenchmarkConfigs.KEY_POOL_SIZE - 1)];
    }
}
