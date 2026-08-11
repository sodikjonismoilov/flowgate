/*
 * Flowgate load test — k6
 * ============================================================================
 *
 * Exercises both HTTP surfaces of flowgate-service and proves the headline
 * multi-tenant claim: one tenant burning through its quota does not affect
 * another tenant's quota.
 *
 *   POST /v1/completions   @RateLimit-protected AI-gateway endpoint.
 *                          Key = the X-API-Key header value (SpEL "#apiKey").
 *                          LlmGatewayController declares:
 *                              algorithm = TOKEN_BUCKET
 *                              limit     = 10
 *                              window    = PT1M   (1 minute)
 *                          RateLimitAspect builds this with
 *                          RateLimiterConfig.tokenBucket(10, PT1M), whose
 *                          two-arg factory sets burstCapacity = 2 * limit = 20.
 *                          So a cold key starts with 20 tokens and refills at
 *                          10 tokens/minute ~= 1 token every 6 seconds.
 *                          Over-quota -> HTTP 429 + Retry-After.
 *
 *   POST /check            Direct rate-limit check (RateLimitCheckController).
 *                          Caller supplies the algorithm/limit/window, so
 *                          there is no server-side configured limit here.
 *                          NOTE: this endpoint always answers HTTP 200; the
 *                          verdict is the boolean "allowed" in the body, not
 *                          the status code. Denials are tracked separately.
 *
 * ---------------------------------------------------------------------------
 * PREREQUISITES
 * ---------------------------------------------------------------------------
 *   1. Redis:    docker compose up -d redis
 *   2. Service:  mvn -pl :flowgate-service -am spring-boot:run
 *
 *                NOTE: service/pom.xml does not declare spring-boot-maven-plugin,
 *                so `mvn package` produces a plain library jar with no Main-Class.
 *                `java -jar service/target/flowgate-service-1.0.0-SNAPSHOT.jar`
 *                will fail with "no main manifest attribute" until that plugin is
 *                added. Use spring-boot:run until then.
 *
 *      Confirm:  curl -s localhost:8080/actuator/health
 *   3. k6:       brew install k6
 *
 * ---------------------------------------------------------------------------
 * RUN  (from the repository root — the JSON summary is written to loadtest/)
 * ---------------------------------------------------------------------------
 *   k6 run loadtest/ratelimit-loadtest.js
 *
 *   # against a non-default host
 *   k6 run -e BASE_URL=http://localhost:8080 loadtest/ratelimit-loadtest.js
 *
 *   # pin the tenant keys instead of generating fresh ones per run
 *   k6 run -e HOT_KEY=acme-corp -e POLITE_KEY=widgetco loadtest/ratelimit-loadtest.js
 *
 * By default setup() mints run-unique API keys. That matters: the Redis token
 * bucket key carries a PEXPIRE of window * 2 = 2 minutes, so reusing a fixed
 * key within two minutes of a previous run inherits its drained bucket and the
 * "polite tenant never gets 429" assertion would fail for the wrong reason.
 *
 * ---------------------------------------------------------------------------
 * WHY 429 DOES NOT FAIL THE RUN
 * ---------------------------------------------------------------------------
 * 429 is the feature, not a defect — the whole point of the hot-tenant scenario
 * is to produce them. k6's built-in http_req_failed metric counts any non-2xx
 * as a failure, so the completions requests install a responseCallback that
 * treats 200 and 429 as expected. The run fails only on:
 *   - any 5xx (server_error_rate)
 *   - the polite tenant ever receiving a 429 (polite_tenant_429_rate)
 *   - latency thresholds (p50 and p99, asserted separately)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/** The limit configured in LlmGatewayController — reported in the summary. */
const COMPLETIONS_LIMIT = 10;
const COMPLETIONS_WINDOW = 'PT1M';
const COMPLETIONS_BURST = COMPLETIONS_LIMIT * 2; // RateLimiterConfig.tokenBucket() default

// ─── Custom metrics ─────────────────────────────────────────────────────────

const completionsLatency = new Trend('completions_latency', true);
const checkLatency = new Trend('check_latency', true);

// Latency split by verdict: a 429 short-circuits before the controller body runs,
// so allowed and rejected requests are not the same amount of work.
const completionsAllowedLatency = new Trend('completions_allowed_latency', true);
const completionsRejectedLatency = new Trend('completions_rejected_latency', true);

const rateLimited429 = new Rate('rate_limited_429_rate');
const politeTenant429 = new Rate('polite_tenant_429_rate');
const serverErrors = new Rate('server_error_rate');
const checkDenied = new Rate('check_denied_rate');

const completions200 = new Counter('completions_200_total');
const completions429 = new Counter('completions_429_total');
const politeTenant200 = new Counter('polite_tenant_200_total');

// Only 200 is expected by default; the completions calls widen this per-request.
http.setResponseCallback(http.expectedStatuses(200));
const ALLOW_200_OR_429 = http.expectedStatuses(200, 429);

// ─── Scenarios ──────────────────────────────────────────────────────────────

export const options = {
  // med is p(50); both p(50) and p(99) are printed for every Trend.
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],

  scenarios: {
    // Tenant A: deliberately abusive. Ramps well past 10 req/min and is
    // expected to spend most of the run receiving 429s.
    hot_tenant: {
      executor: 'ramping-vus',
      exec: 'hotTenant',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 10 }, // ramp up
        { duration: '30s', target: 10 }, // hold
        { duration: '15s', target: 25 }, // spike
        { duration: '20s', target: 25 }, // hold the spike
        { duration: '10s', target: 0 },  // ramp down
      ],
      gracefulRampDown: '5s',
      tags: { tenant: 'hot' },
    },

    // Tenant B: well-behaved. One VU pacing at ~1 request per 10s consumes
    // ~9 requests over the 90s run, comfortably inside the 20-token burst
    // capacity, so it must never see a 429 no matter what tenant A does.
    // That is the multi-tenant isolation assertion.
    polite_tenant: {
      executor: 'constant-vus',
      exec: 'politeTenant',
      vus: 1,
      duration: '90s',
      tags: { tenant: 'polite' },
    },

    // The direct check endpoint, with its own ramping profile. Limits are
    // supplied by the caller here, so this measures raw enforcement latency
    // rather than quota behaviour.
    check_endpoint: {
      executor: 'ramping-vus',
      exec: 'checkEndpoint',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 5 },
        { duration: '30s', target: 15 },
        { duration: '25s', target: 15 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { surface: 'check' },
    },
  },

  thresholds: {
    // p50 and p99 asserted as separate entries so each is reported and can
    // fail independently — an averaged single threshold would hide the tail.
    'completions_latency': ['p(50)<100', 'p(99)<750'],
    'check_latency': ['p(50)<100', 'p(99)<750'],

    // Hard failures.
    'server_error_rate': ['rate==0'],
    'polite_tenant_429_rate': ['rate==0'],

    // Informational only: recorded and printed, never fails the run. 429s from
    // the hot tenant are the expected outcome, so any bound here would be
    // arbitrary. abortOnFail is off and the threshold is trivially true.
    'rate_limited_429_rate': ['rate>=0'],
    'check_denied_rate': ['rate>=0'],
  },
};

// ─── Setup ──────────────────────────────────────────────────────────────────

export function setup() {
  const res = http.get(`${BASE_URL}/actuator/health`, {
    responseCallback: http.expectedStatuses(200, 404),
  });
  if (res.status === 0) {
    throw new Error(
      `flowgate-service is not reachable at ${BASE_URL}. ` +
      `Start Redis (docker compose up -d redis) then the service ` +
      `(java -jar service/target/flowgate-service-1.0.0-SNAPSHOT.jar).`
    );
  }

  // Run-unique keys unless overridden. See the header note on the 2-minute PEXPIRE.
  const runId = Date.now().toString(36);
  return {
    hotKey: __ENV.HOT_KEY || `k6-hot-${runId}`,
    politeKey: __ENV.POLITE_KEY || `k6-polite-${runId}`,
    checkTenant: __ENV.CHECK_TENANT || `k6-check-${runId}`,
  };
}

// ─── Scenario: hot tenant ───────────────────────────────────────────────────

export function hotTenant(data) {
  const res = http.post(
    `${BASE_URL}/v1/completions`,
    JSON.stringify({ prompt: 'Summarise the CAP theorem in one sentence.' }),
    {
      headers: { 'Content-Type': 'application/json', 'X-API-Key': data.hotKey },
      responseCallback: ALLOW_200_OR_429, // 429 is a valid outcome here
      tags: { name: 'POST /v1/completions', tenant: 'hot' },
    }
  );

  recordCompletion(res, false);

  check(res, {
    'hot: status is 200 or 429': (r) => r.status === 200 || r.status === 429,
    'hot: 429 carries Retry-After': (r) =>
      r.status !== 429 || r.headers['Retry-After'] !== undefined,
    'hot: 429 body is rate_limit_exceeded': (r) => {
      if (r.status !== 429) return true;
      try {
        return r.json('error') === 'rate_limit_exceeded';
      } catch (e) {
        return false;
      }
    },
  });

  sleep(0.5);
}

// ─── Scenario: polite tenant ────────────────────────────────────────────────

export function politeTenant(data) {
  const res = http.post(
    `${BASE_URL}/v1/completions`,
    JSON.stringify({ prompt: 'Ping.' }),
    {
      headers: { 'Content-Type': 'application/json', 'X-API-Key': data.politeKey },
      responseCallback: ALLOW_200_OR_429,
      tags: { name: 'POST /v1/completions', tenant: 'polite' },
    }
  );

  recordCompletion(res, true);

  check(res, {
    'polite: status is 200 (never rate limited)': (r) => r.status === 200,
    'polite: response echoes the tenant': (r) => {
      if (r.status !== 200) return false;
      try {
        return r.json('tenantId') === data.politeKey;
      } catch (e) {
        return false;
      }
    },
  });

  // ~1 request per 10s: 9 requests over the 90s run, well inside the 20-token
  // burst, so this tenant can never exhaust its quota on its own.
  sleep(10);
}

// ─── Scenario: /check ───────────────────────────────────────────────────────

export function checkEndpoint(data) {
  const res = http.post(
    `${BASE_URL}/check`,
    JSON.stringify({
      tenantId: data.checkTenant,
      key: `user-${__VU}`,
      algorithm: 'SLIDING_WINDOW_COUNTER',
      limit: 100,
      windowMillis: 60000,
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'POST /check' },
    }
  );

  checkLatency.add(res.timings.duration);
  serverErrors.add(res.status >= 500);

  let allowed = null;
  if (res.status === 200) {
    try {
      allowed = res.json('allowed');
    } catch (e) {
      allowed = null;
    }
  }
  // /check answers 200 whatever the verdict, so the denial rate has to come
  // from the body rather than the status code.
  if (allowed !== null) checkDenied.add(allowed === false);

  check(res, {
    'check: status is 200': (r) => r.status === 200,
    'check: body has an allowed verdict': () => allowed !== null,
    'check: body has remaining': (r) => {
      if (r.status !== 200) return false;
      try {
        return typeof r.json('remaining') === 'number';
      } catch (e) {
        return false;
      }
    },
  });

  sleep(0.3);
}

// ─── Shared recording ───────────────────────────────────────────────────────

function recordCompletion(res, isPolite) {
  const d = res.timings.duration;
  completionsLatency.add(d);
  serverErrors.add(res.status >= 500);

  const limited = res.status === 429;
  rateLimited429.add(limited);

  if (limited) {
    completions429.add(1);
    completionsRejectedLatency.add(d);
  } else if (res.status === 200) {
    completions200.add(1);
    completionsAllowedLatency.add(d);
    if (isPolite) politeTenant200.add(1);
  }

  if (isPolite) politeTenant429.add(limited);
}

// ─── Summary ────────────────────────────────────────────────────────────────
//
// Hand-rolled rather than importing jslib.k6.io/k6-summary so the script has no
// network dependency at run time. Prints copy-pasteable p50/p99 numbers.

function num(v, digits) {
  if (v === undefined || v === null || Number.isNaN(v)) return 'n/a';
  return v.toFixed(digits === undefined ? 2 : digits);
}

function trendRow(label, metric) {
  if (!metric) return `  ${label.padEnd(30)} (not recorded)`;
  const v = metric.values;
  return (
    `  ${label.padEnd(30)}` +
    ` count=${String(v.count).padStart(7)}` +
    ` p50=${num(v['med']).padStart(9)}ms` +
    ` p90=${num(v['p(90)']).padStart(9)}ms` +
    ` p95=${num(v['p(95)']).padStart(9)}ms` +
    ` p99=${num(v['p(99)']).padStart(9)}ms` +
    ` max=${num(v['max']).padStart(9)}ms`
  );
}

function rateRow(label, metric) {
  if (!metric) return `  ${label.padEnd(30)} (not recorded)`;
  const v = metric.values;
  const pct = (v.rate * 100).toFixed(2);
  return `  ${label.padEnd(30)} ${pct.padStart(7)}%   (${v.passes} of ${v.passes + v.fails})`;
}

function counterVal(m) {
  return m ? m.values.count : 0;
}

export function handleSummary(data) {
  const m = data.metrics;

  const total429 = counterVal(m['completions_429_total']);
  const total200 = counterVal(m['completions_200_total']);
  const politeOk = counterVal(m['polite_tenant_200_total']);
  // For a Rate metric, `passes` is the count of true observations — here, 429s.
  const polite429m = m['polite_tenant_429_rate'];
  const politeBad = polite429m ? polite429m.values.passes : 0;

  const thresholdFailures = [];
  for (const name of Object.keys(m)) {
    const t = m[name].thresholds;
    if (!t) continue;
    for (const expr of Object.keys(t)) {
      if (t[expr].ok === false) thresholdFailures.push(`${name}  ${expr}`);
    }
  }

  const lines = [];
  lines.push('');
  lines.push('='.repeat(100));
  lines.push('FLOWGATE LOAD TEST SUMMARY');
  lines.push('='.repeat(100));
  lines.push(`Target                 : ${BASE_URL}`);
  lines.push(`Configured limit found : POST /v1/completions -> @RateLimit(TOKEN_BUCKET, key="#apiKey", `
    + `limit=${COMPLETIONS_LIMIT}, window=${COMPLETIONS_WINDOW})`);
  lines.push(`                         effective burst capacity = ${COMPLETIONS_BURST} tokens `
    + `(RateLimiterConfig.tokenBucket sets burstCapacity = 2 x limit)`);
  lines.push(`                         refill rate = ${COMPLETIONS_LIMIT} tokens/min `
    + `(~1 token every ${(60 / COMPLETIONS_LIMIT).toFixed(1)}s)`);
  lines.push(`                         POST /check has no server-side limit — the caller supplies it.`);
  lines.push('');
  lines.push('LATENCY (p50 / p99 are the numbers to quote)');
  lines.push('-'.repeat(100));
  lines.push(trendRow('POST /v1/completions (all)', m['completions_latency']));
  lines.push(trendRow('  .. allowed (200)', m['completions_allowed_latency']));
  lines.push(trendRow('  .. rejected (429)', m['completions_rejected_latency']));
  lines.push(trendRow('POST /check', m['check_latency']));
  lines.push(trendRow('http_req_duration (all)', m['http_req_duration']));
  lines.push('');
  lines.push('RATE LIMITING BEHAVIOUR');
  lines.push('-'.repeat(100));
  lines.push(rateRow('429 rate (all completions)', m['rate_limited_429_rate']));
  lines.push(rateRow('429 rate (polite tenant)', m['polite_tenant_429_rate']));
  lines.push(rateRow('denied rate (POST /check)', m['check_denied_rate']));
  lines.push(rateRow('5xx rate', m['server_error_rate']));
  lines.push('');
  lines.push(`  completions 200        : ${total200}`);
  lines.push(`  completions 429        : ${total429}`);
  lines.push(`  polite tenant 200      : ${politeOk}`);
  lines.push(`  polite tenant 429      : ${politeBad}   <- must be 0 (multi-tenant isolation)`);
  lines.push('');
  lines.push('VERDICT');
  lines.push('-'.repeat(100));
  if (thresholdFailures.length === 0) {
    lines.push('  PASS — no 5xx, polite tenant never rate limited, latency thresholds met.');
  } else {
    lines.push('  FAIL — thresholds breached:');
    for (const f of thresholdFailures) lines.push(`    - ${f}`);
  }
  lines.push('='.repeat(100));
  lines.push('');

  return {
    stdout: lines.join('\n'),
    'loadtest/summary.json': JSON.stringify(data, null, 2),
  };
}
