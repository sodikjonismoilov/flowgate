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
 * Throughput view: operations per second for all eight permutations
 * (four algorithms x {in-memory, Redis}).
 *
 * <p>Pair this with {@link RateLimiterLatencyBenchmark}, which runs the same eight
 * permutations in {@link Mode#SampleTime} to produce a latency distribution. Throughput
 * alone hides tail behaviour: a limiter averaging 30k ops/sec with a 40 ms p99 and one
 * averaging 30k ops/sec with a 0.2 ms p99 are very different products.
 *
 * <h3>Run</h3>
 * <pre>
 *   docker compose up -d redis
 *   mvn clean package -Pbenchmarks -DskipTests
 *   java -jar benchmark/target/benchmarks.jar RateLimiterThroughputBenchmark
 *
 *   # in-memory only, no Redis required:
 *   java -jar benchmark/target/benchmarks.jar ".*Throughput.*inMemory"
 *
 *   # add allocation profiling:
 *   java -jar benchmark/target/benchmarks.jar RateLimiterThroughputBenchmark -prof gc
 * </pre>
 *
 * <h3>Why JMH rather than a loop and {@code System.nanoTime()}</h3>
 * JIT compilation, dead-code elimination, constant folding and on-stack replacement
 * make hand-rolled JVM timing loops wrong by an order of magnitude or more. JMH forks a
 * clean JVM, warms the JIT to steady state, and consumes results through a
 * {@link Blackhole} so the optimiser cannot delete the work being measured.
 *
 * <p>Every timing annotation below is stated explicitly rather than inherited from
 * JMH's defaults (which are 5 warmup + 5 measurement iterations of 10 seconds across
 * 5 forks — around 40 minutes for this suite). The settings here put a full run in the
 * few-minutes range while still giving the JIT enough warmup to reach steady state.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 0)
@Threads(1)
public class RateLimiterThroughputBenchmark {

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
                .include(RateLimiterThroughputBenchmark.class.getSimpleName())
                .build()).run();
    }
}
