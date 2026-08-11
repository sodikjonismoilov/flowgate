package com.flowgate.benchmark;

import com.flowgate.core.RateLimiter;
import com.flowgate.core.redis.RedisLeakyBucketRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowCounterRateLimiter;
import com.flowgate.core.redis.RedisSlidingWindowLogRateLimiter;
import com.flowgate.core.redis.RedisTokenBucketRateLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.time.Duration;

/**
 * Holds the four Redis-backed limiters, the Lettuce client, and the rotating key pool.
 *
 * <h3>Where Redis comes from</h3>
 * A plain connection to {@code localhost:6379} — the same instance
 * {@code docker-compose.yml} defines as service {@code redis} (container
 * {@code flowgate-redis}). Start it before running the suite:
 * <pre>
 *   docker compose up -d redis
 * </pre>
 * Testcontainers was considered and rejected: it would pull the Docker Java client,
 * JNA and a chunk of the JUnit ecosystem into the shaded {@code benchmarks.jar},
 * and would start a fresh container inside every JMH fork — measurable startup cost
 * for zero measurement benefit. Host and port can be overridden with the
 * {@code flowgate.bench.redis.host} / {@code flowgate.bench.redis.port} system
 * properties, e.g. to measure the cost of a non-local Redis hop.
 *
 * <p>If Redis is not reachable, {@link #setup()} fails immediately with an
 * actionable message rather than letting JMH report a mysterious per-op error.
 */
@State(Scope.Benchmark)
public class RedisLimiterState {

    private static final String HOST = System.getProperty("flowgate.bench.redis.host", "localhost");
    private static final int PORT = Integer.getInteger("flowgate.bench.redis.port", 6379);

    /** Key prefixes the core limiters write under — used to clean up after the run. */
    private static final String[] PREFIXES = {
            "flowgate:token_bucket:",
            "flowgate:leaky_bucket:",
            "flowgate:sliding_window_log:",
            "flowgate:sliding_window_counter:"
    };

    public RateLimiter tokenBucket;
    public RateLimiter leakyBucket;
    public RateLimiter slidingWindowLog;
    public RateLimiter slidingWindowCounter;

    private RedisClient client;
    private StatefulRedisConnection<String, String> probe;
    private String[] keys;
    private int cursor;

    /**
     * Runs once per trial (not per iteration): opening five Lettuce connections and
     * loading the Lua scripts is expensive, and unlike the in-memory case there is no
     * unbounded heap growth to reset — every key carries a PEXPIRE shorter than the
     * gap between iterations, so Redis prunes its own state.
     */
    @Setup(Level.Trial)
    public void setup() {
        RedisURI uri = RedisURI.Builder.redis(HOST, PORT)
                .withTimeout(Duration.ofSeconds(5))
                .build();
        client = RedisClient.create(uri);

        try {
            probe = client.connect();
            probe.sync().ping();
        } catch (RuntimeException e) {
            if (client != null) {
                client.shutdown();
            }
            throw new IllegalStateException(
                    "Redis is not reachable at " + HOST + ":" + PORT + ". "
                            + "Start it with:  docker compose up -d redis   "
                            + "(or point the benchmark elsewhere with "
                            + "-Dflowgate.bench.redis.host=... -Dflowgate.bench.redis.port=...)", e);
        }

        keys = BenchmarkConfigs.keyPool("redis");
        cursor = 0;
        deleteBenchmarkKeys();

        // Each constructor opens its own connection and the first tryAcquire EVALs the
        // Lua script, which Redis then caches. Both costs are paid here, outside the
        // measured region, so the benchmark measures steady-state script execution.
        tokenBucket = new RedisTokenBucketRateLimiter(BenchmarkConfigs.tokenBucket(), client);
        leakyBucket = new RedisLeakyBucketRateLimiter(BenchmarkConfigs.leakyBucket(), client);
        slidingWindowLog = new RedisSlidingWindowLogRateLimiter(BenchmarkConfigs.slidingWindowLog(), client);
        slidingWindowCounter = new RedisSlidingWindowCounterRateLimiter(
                BenchmarkConfigs.slidingWindowCounter(), client);

        String warmKey = "bench:redis:warmup";
        tokenBucket.tryAcquire(warmKey);
        leakyBucket.tryAcquire(warmKey);
        slidingWindowLog.tryAcquire(warmKey);
        slidingWindowCounter.tryAcquire(warmKey);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        try {
            deleteBenchmarkKeys();
        } finally {
            if (probe != null) {
                probe.close();
            }
            if (client != null) {
                // Shuts down the shared Netty event loop and every connection opened from it,
                // including the four the limiters hold internally.
                client.shutdown();
            }
        }
    }

    /**
     * Removes only the keys this benchmark created. Deliberately not FLUSHDB — the
     * benchmark points at the developer's local Redis and must not destroy anything
     * else living in it.
     */
    private void deleteBenchmarkKeys() {
        if (probe == null || keys == null) {
            return;
        }
        String[] doomed = new String[PREFIXES.length * (keys.length + 1)];
        int i = 0;
        for (String prefix : PREFIXES) {
            for (String key : keys) {
                doomed[i++] = prefix + key;
            }
            doomed[i++] = prefix + "bench:redis:warmup";
        }
        probe.sync().del(doomed);
    }

    /** Next key in the rotation. See {@link InMemoryLimiterState#nextKey()} for why this is unsynchronised. */
    public String nextKey() {
        return keys[(cursor++) & (BenchmarkConfigs.KEY_POOL_SIZE - 1)];
    }
}
