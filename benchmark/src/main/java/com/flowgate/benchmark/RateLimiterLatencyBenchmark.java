package com.flowgate.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Latency view: the same eight permutations as {@link RateLimiterThroughputBenchmark},
 * measured in {@link Mode#SampleTime}.
 *
 * <h3>Why a separate class instead of {@code @BenchmarkMode({Throughput, SampleTime})}</h3>
 * Two reasons. First, {@link OutputTimeUnit} is per-class: throughput wants ops/second,
 * latency wants microseconds, and one annotation cannot serve both without producing
 * numbers like "0.00003 ops/us". Second, the two views answer different questions and
 * are usually run at different times — capacity planning uses throughput, SLO work uses
 * the tail — so keeping them separable by class name is more useful than coupling them.
 *
 * <h3>Why SampleTime rather than AverageTime</h3>
 * {@code AverageTime} reports a single mean, which is exactly the statistic that hides
 * the problem: a rate limiter in the request path is judged on its tail, not its mean.
 * {@code SampleTime} records individual invocation timings and JMH prints the full
 * percentile table — p50, p90, p95, p99, p99.9, p99.99 and max — in one run. Read p99
 * from the {@code ·p0.99} row of the output.
 *
 * <h3>Run</h3>
 * <pre>
 *   docker compose up -d redis
 *   java -jar benchmark/target/benchmarks.jar RateLimiterLatencyBenchmark
 * </pre>
 *
 * <p>Note on sampling bias: at in-memory speeds a single {@code tryAcquire} costs tens
 * of nanoseconds, which is close to the resolution of the timestamp call itself, so
 * JMH sub-samples and the reported low percentiles are quantised. That is expected and
 * is why the throughput view remains the authoritative number for in-memory limiters;
 * the latency view is what matters for the Redis-backed ones, where a network round
 * trip dwarfs any measurement overhead.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 0)
@Threads(1)
public class RateLimiterLatencyBenchmark {

    // ─── In-memory ────────────────────────────────────────────────────────────

    @Benchmark
    public void tokenBucket_inMemory(InMemoryLimiterState state, Blackhole bh) {
        bh.consume(state.tokenBucket.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void leakyBucket_inMemory(InMemoryLimiterState state, Blackhole bh) {
        bh.consume(state.leakyBucket.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void slidingWindowLog_inMemory(InMemoryLimiterState state, Blackhole bh) {
        bh.consume(state.slidingWindowLog.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void slidingWindowCounter_inMemory(InMemoryLimiterState state, Blackhole bh) {
        bh.consume(state.slidingWindowCounter.tryAcquire(state.nextKey()));
    }

    // ─── Redis-backed (requires a live Redis on localhost:6379) ────────────────

    @Benchmark
    public void tokenBucket_redis(RedisLimiterState state, Blackhole bh) {
        bh.consume(state.tokenBucket.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void leakyBucket_redis(RedisLimiterState state, Blackhole bh) {
        bh.consume(state.leakyBucket.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void slidingWindowLog_redis(RedisLimiterState state, Blackhole bh) {
        bh.consume(state.slidingWindowLog.tryAcquire(state.nextKey()));
    }

    @Benchmark
    public void slidingWindowCounter_redis(RedisLimiterState state, Blackhole bh) {
        bh.consume(state.slidingWindowCounter.tryAcquire(state.nextKey()));
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(RateLimiterLatencyBenchmark.class.getSimpleName())
                .build()).run();
    }
}
