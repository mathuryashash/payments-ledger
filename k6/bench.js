/**
 * k6 load test for POST /api/v1/transfers
 *
 * Usage:
 *   k6 run --env ALICE_ID=<uuid> --env BOB_ID=<uuid> k6/bench.js
 *
 * Run from project root after `make up`.
 * Account IDs must be pre-seeded (see VERIFY.md for setup commands).
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:18200";
const ALICE_ID = __ENV.ALICE_ID || "";
const BOB_ID   = __ENV.BOB_ID   || "";

const errorCount = new Counter("transfer_errors");
const transferDuration = new Trend("transfer_duration", true);

// 3 scenarios: low / medium / hot accounts
export const options = {
  scenarios: {
    spread_low: {
      executor: "constant-vus",
      vus: 4,
      duration: "30s",
      env: { SCENARIO: "spread" },
      tags: { scenario: "spread_low" },
    },
    spread_high: {
      executor: "constant-vus",
      vus: 16,
      duration: "30s",
      startTime: "35s",
      env: { SCENARIO: "spread" },
      tags: { scenario: "spread_high" },
    },
    hot_accounts: {
      executor: "constant-vus",
      vus: 16,
      duration: "30s",
      startTime: "70s",
      env: { SCENARIO: "hot" },
      tags: { scenario: "hot_accounts" },
    },
  },
  thresholds: {
    "http_req_failed": ["rate<0.05"],           // <5% error rate
    "http_req_duration{scenario:spread_low}": ["p(95)<500"],
    "http_req_duration{scenario:spread_high}": ["p(95)<800"],
    "http_req_duration{scenario:hot_accounts}": ["p(95)<1200"],
  },
};

function randomId() {
  return "k6-" + Math.random().toString(36).substring(2, 15) + Date.now();
}

export default function () {
  const idemKey = randomId();
  const scenario = __ENV.SCENARIO || "spread";

  let fromId, toId;
  if (scenario === "hot" && ALICE_ID && BOB_ID) {
    // All VUs hammer the same 2 accounts — maximum contention
    fromId = ALICE_ID;
    toId = BOB_ID;
  } else {
    // Spread: use the env IDs if present, else use same (server returns 422 on no funds but that's ok)
    fromId = ALICE_ID || "00000000-0000-0000-0000-000000000001";
    toId   = BOB_ID   || "00000000-0000-0000-0000-000000000002";
  }

  const payload = JSON.stringify({
    reference: "k6-bench-" + idemKey,
    postings: [
      { accountId: fromId, direction: "DEBIT",  amountMinor: 100, currency: "USD" },
      { accountId: toId,   direction: "CREDIT", amountMinor: 100, currency: "USD" },
    ],
  });

  const start = Date.now();
  const res = http.post(`${BASE}/api/v1/transfers`, payload, {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idemKey,
    },
  });
  transferDuration.add(Date.now() - start);

  const ok = check(res, {
    "status 201 or 422": (r) => r.status === 201 || r.status === 422,
  });
  if (!ok) errorCount.add(1);

  sleep(0.05);
}
