# Flowgate — Architecture Guide

> A junior-developer-friendly walkthrough of the codebase.

---

## 1. What is this project, in one sentence?

Flowgate is a **rate limiter** — software that answers one question very fast, over and over:

> *"This user just made a request. Should I allow it, or have they made too many requests too quickly?"*

Think of a bouncer at a club who only lets in 100 people per minute. Everyone else waits. Rate limiters protect APIs from abuse, runaway scripts, and traffic spikes that would otherwise crash your servers.

The clever twist: it ships the **same core logic** in **two different shapes**:

- **Mode 1 — a library (embedded JAR):** drop it into your own Java/Spring app and put `@RateLimit` on a method. Done.
- **Mode 2 — a standalone service:** a separate server that *any* language (Python, Go, Node) can ask "is this allowed?" over the network.

Both modes share one brain: the `core` module.

---

## 2. Big-picture architecture

```
                          ┌─────────────────────────────────────────┐
   MODE 1 (in-process)    │   Your Spring Boot App                   │
                          │                                          │
   @RateLimit ───────────►│   flowgate-library                       │
   on a method            │   (AOP Aspect intercepts the call)       │
                          └───────────────┬──────────────────────────┘
                                          │
                                          │  calls
                                          ▼
                          ┌─────────────────────────────────────────┐
                          │   flowgate-core   ◄── THE BRAIN          │
                          │                                          │
                          │   RateLimiter interface                  │
                          │     ├─ TokenBucketRateLimiter            │
                          │     ├─ LeakyBucketRateLimiter            │
                          │     ├─ SlidingWindowLogRateLimiter       │
                          │     └─ SlidingWindowCounter (not built)  │
                          └───────────────▲──────────────────────────┘
                                          │  calls
                                          │
                          ┌───────────────┴──────────────────────────┐
   MODE 2 (over network)  │   flowgate-service (Spring Boot server)  │
                          │   POST /check  { key, limit, window }    │
   Python/Go/Node ───────►│   → { allowed: true, remaining: 73 }     │
                          └──────────────────────────────────────────┘

   Future (Week 3+):  core ──► Redis  (shared state across many servers)
   Future (Week 7):   service ──► Prometheus ──► Grafana  (dashboards)
```

The key insight: **`flowgate-core` has zero dependency on Spring, Redis, or the web.** It's pure Java. The library and the service are just two different "front doors" into the same algorithms. The hard logic is isolated and testable on its own.

---

## 3. The modules (Maven multi-module project)

The root [`pom.xml`](pom.xml) is a *parent* that ties four child modules together:

| Module | What it is | Depends on Spring? |
|---|---|---|
| [`core/`](core/) | The algorithms. Pure Java, no frameworks. | No |
| [`library/`](library/) | The `@RateLimit` annotation + AOP wiring. | Yes (AOP) |
| [`service/`](service/) | A standalone web server wrapping core. | Yes (Web) |
| [`benchmark/`](benchmark/) | JMH speed tests. Off by default. | No |

**What is a multi-module build?** One project split into folders that build into separate JARs but version together. The parent pom declares the Java version (21), pulls in Spring's "Bill of Materials" so every child gets matching library versions for free, and lists which modules to build.

---

## 4. Core contracts — read these first

These three files are the **vocabulary** of the whole project. Everything else uses them.

### [`RateLimiter.java`](core/src/main/java/com/flowgate/core/RateLimiter.java) — the interface

Every algorithm promises to implement this:

```java
RateLimitResult tryAcquire(String key);             // "can user X make 1 request?"
RateLimitResult tryAcquire(String key, int tokens); // "...make a request worth N tokens?"
RateLimiterConfig config();
```

The `key` is *who* you're limiting — `"user:42"`, `"ip:203.0.113.1"`, etc. Different keys are counted independently. The big rule: **every implementation must be thread-safe**, because many web requests hit it at the same time.

### [`RateLimitResult.java`](core/src/main/java/com/flowgate/core/model/RateLimitResult.java) — the answer

A `record` (immutable data holder) that carries back everything the caller needs:

| Field | Purpose |
|---|---|
| `allowed` | Yes or no |
| `remaining` | Requests left in the current window |
| `resetAt` | When the quota refreshes |
| `retryAfter` | How long to wait (used for the HTTP `Retry-After` header on a 429) |

### [`RateLimiterConfig.java`](core/src/main/java/com/flowgate/core/RateLimiterConfig.java) — the settings

Holds the algorithm, `limit`, `window` (time period), and `burstCapacity`. Use the **factory methods** — they bake in sensible defaults:

```java
RateLimiterConfig.tokenBucket(100, Duration.ofMinutes(1))   // 100 req/min, burst 200
RateLimiterConfig.leakyBucket(50, Duration.ofSeconds(10))
RateLimiterConfig.slidingWindow(SLIDING_WINDOW_LOG, 200, Duration.ofMinutes(1))
```

The compact constructor validates inputs (limit > 0, window positive, etc.) so you can never build a nonsensical config.

### [`NanoClock.java`](core/src/main/java/com/flowgate/core/NanoClock.java) — testable time

A tiny abstraction over `System.nanoTime()`. Production passes `System::nanoTime`; tests pass a fake clock they control — so you can test "what happens after 60 seconds" without actually waiting 60 seconds. A common, clean testing pattern worth memorising.

---

## 5. The algorithms

Three of the four are implemented. They all share the **same concurrency pattern**.

### The shared trick: lock-free CAS loop

Look at [`TokenBucketRateLimiter.java`](core/src/main/java/com/flowgate/core/inmemory/TokenBucketRateLimiter.java):

```java
ConcurrentHashMap<String, AtomicReference<BucketState>> buckets;
```

One map entry per key (per user). The state is wrapped in an `AtomicReference` and updated with a **CAS (Compare-And-Set) loop**:

```java
while (true) {
    BucketState current = ref.get();           // 1. read current state
    // ... compute new state ...
    if (ref.compareAndSet(current, newState))  // 2. commit ONLY if no one else changed it
        return result;                         // 3. success
    // else: someone else won the race → loop and retry
}
```

Instead of locking (which makes threads queue up and wait), it optimistically computes the answer and commits only if no other thread interfered. If two requests collide, one retries. This is fast and lock-free.

---

### 🪣 Token Bucket — [`TokenBucketRateLimiter.java`](core/src/main/java/com/flowgate/core/inmemory/TokenBucketRateLimiter.java)

A bucket holds tokens. Each request spends one. Tokens refill at a steady rate over time. If the bucket is empty → rejected.

Because the bucket can hold up to `burstCapacity` tokens, a quiet user can "save up" and then **burst**. This is what AWS API Gateway and Stripe use.

The key line:
```java
refillTokensPerNano = (double) config.limit() / config.window().toNanos();
```
Converts "100 per minute" into "this many tokens per nanosecond". No background timer thread — refill is computed lazily on each read.

---

### 💧 Leaky Bucket — [`LeakyBucketRateLimiter.java`](core/src/main/java/com/flowgate/core/inmemory/LeakyBucketRateLimiter.java)

The mirror image of token bucket. The bucket *fills* with requests and *leaks* (drains) at a constant rate. If a new request would overflow the bucket → rejected.

Result: traffic comes out **perfectly smooth**, no bursts allowed. Good for protecting a fragile downstream system from sudden spikes.

State stored per key: `{ lastLeakNanos, currentLevel }`. On each call, leaked amount is subtracted before the new request is added.

---

### 📜 Sliding Window Log — [`SlidingWindowLogRateLimiter.java`](core/src/main/java/com/flowgate/core/inmemory/SlidingWindowLogRateLimiter.java)

The most **accurate** and most **expensive**. Stores the actual timestamp of every request in an `ArrayDeque`. On each call:

1. Throw away timestamps older than the window.
2. Check if count ≥ limit → reject.
3. Otherwise add the current timestamp and allow.

Exact accuracy, but memory grows with traffic (1 deque entry per request in the window).

This one uses a **different concurrency approach**: `windows.compute(key, ...)`. `ConcurrentHashMap.compute` holds a short per-key lock during the lambda — atomic check-then-act for one key without blocking others. Returning `null` inside the lambda evicts empty entries (lazy memory cleanup).

---

### ⏳ Sliding Window Counter — *not yet implemented*

Defined in [`Algorithm.java`](core/src/main/java/com/flowgate/core/Algorithm.java) and described in the README. Uses a weighted blend of two fixed-window counters to approximate a true sliding window at O(1) memory:

```
effective_count = current_window_requests
                + previous_window_requests × (1 - elapsed_fraction)
```

~99% accurate and Cloudflare's production default. **Week 2 work.**

---

## 6. The library — how `@RateLimit` will work

This is Mode 1. The annotation exists and the skeleton is in place, but enforcement is not wired yet (Week 4).

### The dream usage

```java
@RateLimit(algorithm = TOKEN_BUCKET, key = "#userId", limit = 100, window = "PT1M")
@GetMapping("/api/data")
public Data getData(@RequestParam String userId) { ... }
```

### How the pieces connect

```
HTTP request
  → Spring MVC
  → RateLimitAspect.enforce()          ← intercepts BEFORE the controller method runs
      → evaluate SpEL key expression   (e.g. "#userId" → "42")
      → call RateLimiter.tryAcquire()
      → if allowed: joinPoint.proceed() → controller method runs
      → if rejected: throw RateLimitExceededException
  → GlobalExceptionHandler catches it  → HTTP 429 with Retry-After header
```

### The files

| File | Status | Purpose |
|---|---|---|
| [`RateLimit.java`](library/src/main/java/com/flowgate/library/annotation/RateLimit.java) | ✅ Done | The annotation with `algorithm`, `key`, `limit`, `window` attributes |
| [`RateLimitAspect.java`](library/src/main/java/com/flowgate/library/aspect/RateLimitAspect.java) | 🚧 Stub | `@Around` advice — currently a pass-through, TODO Week 4 |
| [`RateLimitExceededException.java`](library/src/main/java/com/flowgate/library/exception/RateLimitExceededException.java) | ✅ Done | Thrown on denial; carries `RateLimitResult` for response headers |
| [`FlowgateAutoConfiguration.java`](library/src/main/java/com/flowgate/library/autoconfigure/FlowgateAutoConfiguration.java) | 🚧 Empty | Spring Boot auto-config; beans registered in Week 4 |

> **Note:** There are two identical `FlowgateAutoConfiguration` classes — one in `autoconfigure/` (registered in the imports file) and a dead-code duplicate in `config/`. The one in `config/` should be deleted.

**What is AOP?** "Aspect-Oriented Programming" lets Spring wrap any annotated method with extra behaviour. The `@Around` annotation means: "run my code *instead of* the target method — I decide whether to call through or throw." Spring does this using dynamic proxies — at startup it wraps your beans in a proxy object that routes calls through the aspect first.

---

## 7. The service & ops (scaffolded — future weeks)

| File | Status | Purpose |
|---|---|---|
| [`FlowgateServiceApplication.java`](service/src/main/java/com/flowgate/service/FlowgateServiceApplication.java) | 🚧 Shell | Bare Spring Boot `main()`. No `/check` endpoint yet (Week 3–5). |
| [`docker-compose.yml`](docker-compose.yml) | ✅ Ready | Spins up Redis + optional Prometheus/Grafana stack. Not used by code yet. |
| [`benchmark/`](benchmark/) | 🚧 Scaffold | JMH speed tests. Will fill the README benchmark table in Week 6. |

---

## 8. Where the project actually stands

| Week | Goal | Reality |
|---|---|---|
| 1 | Token Bucket, in-memory, tested | ✅ Done (+ 2 bonus algorithms) |
| 2 | All 4 algorithms behind one interface | 🟡 3 of 4 done; Sliding Window Counter missing |
| 3 | Redis backend + atomic Lua scripts | ❌ Not started — everything is in-memory |
| 4 | `@RateLimit` annotation actually enforcing | ❌ Aspect is a pass-through stub |
| 5 | Standalone gRPC/REST service endpoint | ❌ Empty Spring Boot shell |
| 6 | JMH benchmarks | ❌ Scaffold only |
| 7 | Prometheus / Grafana | ❌ Docker config staged, no metrics code yet |

**The engine (core algorithms) is real and working in memory. Everything that turns it into a product — Redis for distributed state, annotation enforcement, the network API, observability — is scaffolded with good explanatory comments but not yet implemented.**

---

## 9. Suggested learning order

1. **Run the tests** — `mvn -pl core test`. Watch the three algorithm tests pass. That's the working part.
2. **Read the 3 core contracts** — `RateLimiter`, `RateLimitResult`, `RateLimiterConfig`. They're small and define everything.
3. **Read `TokenBucketRateLimiter` carefully** — focus on the CAS `while(true)` loop. Once it clicks, the other two are variations.
4. **Trace the library flow** — the execution diagram in `RateLimitAspect.java`'s Javadoc shows where a request travels end-to-end.
5. **Ignore service/benchmark/docker for now** — they're future weeks, mostly empty.
