# VERIFY.md — reproducible evidence for all numbers

## Setup

Machine: Windows 11 laptop, Docker Desktop 4.87 (WSL2), Intel i7-12th gen, 16 GB RAM.
Java: OpenJDK 21.0.9 (JetBrains). Maven 3.9.16.
Testcontainers: 1.20.4 (requires `~/.docker-java.properties` with `api.version=1.44` on Docker Desktop 29.x).

## Tests

```
./mvnw -B verify --no-transfer-progress
```

Output (trimmed):
```
[INFO] Tests run: 1, Failures: 0, Errors: 0  -- ConcurrencyInvariantTest
[INFO] Tests run: 9, Failures: 0, Errors: 0  -- TransferDomainTest
[INFO] Tests run: 1, Failures: 0, Errors: 0  -- OutboxKafkaIntegrationTest
[INFO] Tests run: 8, Failures: 0, Errors: 0  -- TransferIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0  -- BalancingPropertyTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## JaCoCo coverage

Parsed from `target/site/jacoco/jacoco.xml` after `./mvnw verify`:

```
INSTRUCTION: 1200/2217 = 54.1%
BRANCH:       66/120   = 55.0%
LINE:        264/449   = 58.8%
METHOD:       41/84    = 48.8%
CLASS:        20/34    = 58.8%
```

## Concurrency invariant test output

```
Concurrency test: succeeded=263, failed(insufficient_funds/retried)=57, threads=32
Total money before=600000, after=600000
```

32 threads × 10 transfers on 6 accounts. Money is conserved, no negative balances, no lost updates.

## Benchmark

Machine: same as above. App running in docker compose (single Postgres, single Kafka node).
Accounts seeded with `allowOverdraft=true` and large balance.

```
k6 run --env ALICE_ID=<uuid> --env BOB_ID=<uuid> k6/bench.js
```

```
  █ TOTAL RESULTS

  checks_total.......: 12557   125.5/s
  checks_succeeded...: 100.00%
  http_req_failed....: 0.00%

  transfer_duration..: avg=35.57ms  min=5ms  med=26ms  max=244ms  p(90)=73ms  p(95)=88ms

  { scenario:spread_low }......: avg=14.56ms  p(50)=12.97ms  p(95)=24.92ms
  { scenario:spread_high }.....: avg=43.51ms  p(50)=34.78ms  p(95)=98.36ms
  { scenario:hot_accounts }....: avg=35.03ms  p(50)=27.02ms  p(95)=82.27ms

  3 scenarios, 100% all thresholds passed
```

## End-to-end smoke test

```bash
# App running at localhost:18200 via docker compose

ALICE=$(curl -sX POST http://localhost:18200/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","currency":"USD","type":"ASSET","allowOverdraft":false}' | jq -r .id)

BOB=$(curl -sX POST http://localhost:18200/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Bob","currency":"USD","type":"ASSET","allowOverdraft":false}' | jq -r .id)

# Seed Alice
curl -sX POST http://localhost:18200/api/v1/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: seed-verify-1' \
  -d "{\"postings\":[{\"accountId\":\"$ALICE\",\"direction\":\"CREDIT\",\"amountMinor\":50000,\"currency\":\"USD\"},{\"accountId\":\"$BOB\",\"direction\":\"DEBIT\",\"amountMinor\":50000,\"currency\":\"USD\"}]}"
# {"status":"POSTED",...}

# Transfer
TXN=$(curl -sX POST http://localhost:18200/api/v1/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: txn-verify-1' \
  -d "{\"reference\":\"coffee\",\"postings\":[{\"accountId\":\"$ALICE\",\"direction\":\"DEBIT\",\"amountMinor\":1000,\"currency\":\"USD\"},{\"accountId\":\"$BOB\",\"direction\":\"CREDIT\",\"amountMinor\":1000,\"currency\":\"USD\"}]}" | jq -r .id)
echo "Transfer ID: $TXN"

# Balance
curl -s http://localhost:18200/api/v1/accounts/$ALICE | jq .balanceMinor
# 49000

# Reversal
curl -sX POST http://localhost:18200/api/v1/transfers/$TXN/reverse \
  -H 'Idempotency-Key: rev-verify-1' | jq .status
# "POSTED"

curl -s http://localhost:18200/api/v1/accounts/$ALICE | jq .balanceMinor
# 50000
```

## What is NOT done

- API controller layer tests (MockMvc) — coverage would rise to ~75% with these.
- Virtual threads vs platform threads benchmark.
- Multi-instance relay test (only single-instance tested).
- k6 test with funded accounts (no overdraft) — would need larger seed amounts.
- Kafka partition count tuning.
