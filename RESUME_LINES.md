# Resume Lines

## Bullets (≤ 30 words each)

- Built a double-entry payments ledger (Java 21 + Spring Boot 3) with idempotency, deadlock-safe lock ordering, and transactional outbox → Kafka at-least-once delivery.
- Verified 40 tests including a 32-thread concurrency invariant (zero lost updates, money conserved); k6 load runs of 96-125 transfers/s on a laptop with 0 errors.
- Implemented outbox pattern + idempotent Kafka consumer; PostgreSQL constraints (FK, CHECK, unique idempotency hash) enforce consistency without distributed transactions.

## Proven keywords (only what the code actually uses and tests)

**Languages/frameworks:** Java 21, Spring Boot 3.3, Spring Data JPA, Spring Kafka, Flyway, Hibernate, Lombok

**Patterns:** Double-entry bookkeeping, Transactional Outbox, Idempotency (SHA-256 key hash + unique constraint), Idempotent consumer (processed_events table), Deadlock-safe lock ordering (ascending UUID)

**Infrastructure:** PostgreSQL (pgvector:pg17), Apache Kafka (KRaft), Docker, Docker Compose

**Testing:** JUnit 5, Testcontainers, jqwik (property-based), JaCoCo (80.8% instruction coverage)

**Observability:** Micrometer, Prometheus, Spring Actuator health/readiness probes

**CI/CD:** GitHub Actions (ubuntu-latest, ./mvnw -B verify), actionlint, Maven Wrapper
