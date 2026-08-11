package com.flowgate.benchmark;

import com.flowgate.core.Algorithm;
import com.flowgate.core.RateLimiterConfig;

import java.time.Duration;

/**
 * Shared configuration for every benchmark in this module.
 *
 * <p>The whole suite lives or dies on one decision: <b>what config do you hand the
 * limiter so that you measure the "allowed" code path rather than the "denied" one?</b>
 * A rate limiter that has saturated is trivially fast — it does a comparison and
 * returns a rejection. Benchmarking that would produce impressive, meaningless numbers.
 *
 * <h3>Decision 1 — limits are set far above achievable throughput</h3>
 * Every algorithm is configured with a limit that a single-threaded benchmark thread
 * cannot possibly exhaust inside one measurement iteration. In-memory limiters top out
 * in the tens of millions of ops/sec; Redis-backed ones are bounded by the TCP round
 * trip at tens of thousands of ops/sec. A limit of 100,000,000 per second is out of
 * reach for both, so every {@code tryAcquire} in the measured region takes the
 * allow branch and does real work: refill maths, CAS retry loop, map lookup,
 * Lua evaluation, network I/O.
 *
 * <h3>Decision 2 — keys rotate over a fixed pool instead of hammering one key</h3>
 * A single key would measure one {@code AtomicReference} CAS loop with a permanently
 * hot cache line, and on the Redis side a single hash slot. That is not what a
 * multi-tenant gateway looks like. {@link #KEY_POOL_SIZE} distinct keys are cycled
 * round-robin so the {@code ConcurrentHashMap} lookup and the Redis keyspace are
 * both genuinely exercised. Unbounded unique keys were rejected deliberately: they
 * would turn the benchmark into a map-growth and allocation benchmark, and on the
 * Redis side would leak keys into the user's database.
 *
 * <h3>Decision 3 — the sliding window log gets a shorter window, on both backends</h3>
 * The log is the one algorithm whose memory is O(requests per window), not O(1).
 * The in-memory implementation appends a boxed {@code Long} per request to an
 * {@code ArrayDeque} and prunes by timestamp. At ~20M ops/sec a one-second window
 * would hold ~20 million boxed longs — well over a gigabyte of heap, and the
 * benchmark would be measuring GC, not the algorithm. So the log runs with a
 * {@value #LOG_WINDOW_MILLIS} ms window, which caps the live deque at a few hundred
 * thousand entries while leaving the per-operation cost (append + amortised single
 * prune) unchanged. Crucially the <b>same</b> window is used for the Redis log too,
 * so the in-memory vs Redis comparison for that algorithm is still apples-to-apples.
 * The limit is scaled down with it and still sits ~30x above what one thread can reach.
 */
public final class BenchmarkConfigs {

    private BenchmarkConfigs() {
    }

    /** Number of distinct keys cycled round-robin. See "Decision 2" above. */
    public static final int KEY_POOL_SIZE = 64;

    /** Window for the three O(1)-memory algorithms. */
    public static final Duration STANDARD_WINDOW = Duration.ofSeconds(1);

    /** Limit for the three O(1)-memory algorithms — unreachable by one thread in one second. */
    public static final int STANDARD_LIMIT = 100_000_000;

    /** Shorter window for the sliding window log only. See "Decision 3" above. */
    public static final long LOG_WINDOW_MILLIS = 10;

    public static final Duration LOG_WINDOW = Duration.ofMillis(LOG_WINDOW_MILLIS);

    /** Limit for the sliding window log — still ~30x above single-thread reach in a 10 ms window. */
    public static final int LOG_LIMIT = 1_000_000;

    // ─── Per-algorithm configs, identical for the in-memory and Redis variants ───

    public static RateLimiterConfig tokenBucket() {
        // Explicit burst capacity: the two-arg factory would compute limit * 2, which at
        // 100M is uncomfortably close to Integer.MAX_VALUE. Equal capacity is enough here
        // because the refill rate alone already outruns the benchmark thread.
        return RateLimiterConfig.tokenBucket(STANDARD_LIMIT, STANDARD_WINDOW, STANDARD_LIMIT);
    }

    public static RateLimiterConfig leakyBucket() {
        return RateLimiterConfig.leakyBucket(STANDARD_LIMIT, STANDARD_WINDOW);
    }

    public static RateLimiterConfig slidingWindowCounter() {
        return RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_COUNTER, STANDARD_LIMIT, STANDARD_WINDOW);
    }

    public static RateLimiterConfig slidingWindowLog() {
        return RateLimiterConfig.slidingWindow(
                Algorithm.SLIDING_WINDOW_LOG, LOG_LIMIT, LOG_WINDOW);
    }

    /**
     * Builds the rotating key pool. Keys are pre-materialised as Strings so that
     * string concatenation and hashing of fresh char arrays are not accidentally
     * folded into the measurement — {@code String.hashCode} is cached after the
     * first call, which is exactly what a real gateway sees for a warm tenant.
     */
    public static String[] keyPool(String namespace) {
        String[] keys = new String[KEY_POOL_SIZE];
        for (int i = 0; i < KEY_POOL_SIZE; i++) {
            keys[i] = "bench:" + namespace + ":tenant-" + i;
            keys[i].hashCode(); // force the hash cache to be populated during setup
        }
        return keys;
    }
}
