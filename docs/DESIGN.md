# Design Notes: Payments Ledger

## Why double-entry?

Every financial value lives in two places: a debit and a credit. This means the total money in the system is always conserved — if Alice loses $100, Bob gains $100. Single-row balance updates can go wrong silently (network partition, app crash after write); double-entry makes the invariant explicit and checkable at any time by summing all entries.

## Balance storage

Balances are stored as BIGINT minor units (cents, paise, etc.) to avoid floating-point rounding errors. A `balance_minor` column on each account is the materialized view of the entry sum. It is updated in the same DB transaction as the entries, so reads are O(1) and history is always consistent.

Trade-off: storing the balance redundantly means two writes per transfer but reads are fast and there is no need to aggregate the full entry history on every balance check.

## Lock ordering to prevent deadlocks

When a transfer touches multiple accounts, each thread issues `SELECT ... FOR UPDATE`. If two transfers touch the same two accounts in opposite orders, they deadlock. The fix: sort account IDs ascending before acquiring locks. Every thread locks in the same global order, so deadlocks are structurally impossible.

Alternative: optimistic locking (version column). Rejected because under high contention it causes many retries and rollbacks with no progress (live-lock). Pessimistic locking under a consistent order gives a clear fairness guarantee.

## Idempotency

The API requires an `Idempotency-Key` header. The key is SHA-256 hashed before storage (so the original key never appears in DB query logs). The hash has a unique constraint in the DB.

Conflict detection: a SHA-256 of the request body is stored embedded in the `reference` column (`hash:<bodyHash>|<originalRef>`). On replay, if the stored body hash differs from the incoming one, we return 409 Conflict.

Trade-off: the body hash is stored in the `reference` field as a prefix, which is slightly ugly. A cleaner alternative is a separate `idempotency_body_hash` column; that would add a column to the transfers table and a migration. The embedded approach keeps the schema minimal.

## Outbox pattern

The outbox pattern solves the dual-write problem: if we write to the DB and then try to publish to Kafka, the app can crash between the two writes. The fix: write the Kafka payload as a row in `outbox_events` in the same DB transaction. A relay process polls this table and publishes to Kafka, then marks the row as published.

Trade-off: the relay polls every 500ms, so there is up to 500ms latency before consumers see the event. Debezium CDC (reading the Postgres WAL) would reduce this to <100ms and eliminate polling overhead, but requires a Debezium deployment and Kafka Connect. For this project the polling approach is simpler to understand and operate.

The `FOR UPDATE SKIP LOCKED` clause lets multiple relay instances run without stepping on each other's rows — a standard pattern for competing consumers in SQL.

## At-least-once delivery + idempotent consumer

Kafka gives at-least-once delivery by design. The consumer re-processes any event it has already seen. To prevent double-processing, we write a `(topic, partition, offset)` row to `processed_events` in the same DB transaction as the consumer's side effects. On redelivery, we check this table and skip if already processed.

## Virtual threads

`spring.threads.virtual.enabled=true` enables Java 21 virtual threads for all Tomcat request threads. This means each request gets its own virtual thread and can block on I/O without consuming a platform thread. This improves throughput under I/O-bound workloads like this one (every request does DB I/O).

The benchmark didn't compare with/without due to time constraints. The numbers should be a lower bound on what platform threads would achieve.

## Concurrency under test

The `ConcurrencyInvariantTest` runs 32 threads × 10 transfers on 6 accounts and verifies three invariants after all threads finish:
1. Sum of all account balances is unchanged.
2. No non-overdraft account has a negative balance.
3. Each account's balance equals its initial balance plus the net of all its entries.

This catches lost updates, over-drawing, and balance corruption that only appear under concurrency.

## Security

- API-key auth filter runs before all routes. Keys are compared by SHA-256 hash (never plaintext). Empty `LEDGER_API_KEYS` env var disables auth (dev mode only).
- In production: use OAuth2/mTLS with per-key rate limits and rotation.
- Idempotency keys and account IDs are never logged beyond their hashes/UUIDs.
- Input validation rejects negative amounts, missing headers, invalid UUIDs.

## Failure modes

| Scenario | What happens |
|---|---|
| App crashes mid-transfer | Transaction rolls back; no partial writes. |
| App crashes after DB commit, before Kafka | Outbox relay retries on restart. |
| Kafka unavailable | Relay logs error and retries on next poll (500ms). DB data is consistent. |
| Duplicate Kafka delivery | Consumer checks `processed_events`; skips duplicate. |
| Deadlock (concurrent transfers) | Spring catches `CannotAcquireLockException`, retries up to 5 times. |
| Insufficient funds | 422 Unprocessable Entity; nothing committed. |
| Same idempotency key, different body | 409 Conflict. |

## How to scale

- Scale app horizontally (stateless); each instance runs its own outbox relay independently (SKIP LOCKED prevents overlap).
- Scale DB: read replicas for balance reads; Postgres partitioning for large entry tables.
- Scale Kafka: add partitions to `ledger.transfers`; consumer group auto-balances.
- For higher throughput: batch multiple transfers in one request, or add a write buffer (Redis) in front of DB.
