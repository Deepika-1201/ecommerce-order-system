# ADR-005: PostgreSQL, a schema per module, Spring Data JDBC

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [ADR-004](ADR-004-modular-monolith.md), [ADR-009](ADR-009-inventory-reservation.md), [consistency model](../consistency-model.md)

## Context

- Stock and coupon counters need atomic conditional updates. Orders need transactions that cover the state change and its outbox rows.
- Peak load is about 2,400 short transactions per second (architecture §16).
- Both sibling projects run PostgreSQL 17. The gateway uses plain JDBC with Flyway and tests on embedded PostgreSQL.
- Aggregates here have child collections: order lines, reservation lines, shipment tracking.

## Problem

Which database, how are modules separated inside it, and how does code access it?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL** | Strong transactions, `SKIP LOCKED`, `LISTEN/NOTIFY`, partial indexes, mature managed offerings; already known | One primary for writes |
| MySQL | Equally mature | Weaker for queue patterns; nothing it adds here |
| Distributed SQL (CockroachDB, YugabyteDB) | Horizontal writes | Higher latency per transaction and contention retries on hot rows; no need at 200 orders/s |

| Data access | Pros | Cons |
|---|---|---|
| JPA / Hibernate | Familiar; `@Version` locking | Lazy loading and dirty checking hide SQL; conditional updates fight the ORM |
| **Spring Data JDBC** | Aggregate-oriented (an aggregate is loaded and saved whole); no lazy loading; DDD-friendly | Rewrites child rows on save; less flexible queries |
| jOOQ | Type-safe SQL | Code generation step; one more tool |
| Plain JDBC (as in the gateway) | Total control | More boilerplate for aggregates with children |

## Decision

(Proposed.)

- **PostgreSQL 17**, matching the sibling projects. Locally in Docker; embedded in tests; RDS Multi-AZ with a synchronous standby in AWS (RPO 0).
- **A schema per module.** No cross-schema joins, foreign keys or transactions; enforced by tests. One application role in V1; a role per module when a module is extracted.
- **Flyway**, with migrations organized per module, so a module's history moves with it at extraction. Expand/contract for every change that runs during a deployment.
- **Spring Data JDBC** for aggregates. **JdbcClient** for the hot paths that need exact SQL: stock and coupon conditional updates, the outbox, the inbox and the sweeps.
- **Keys:** UUIDv7 for aggregates (time-ordered, so indexes stay compact); `bigserial` for the outbox order.
- **Money:** `bigint` paise. **Time:** `timestamptz`, with the application clock truncated to microseconds to match it. That is a lesson from Payment-Orchestrator, whose Linux CI caught nanosecond clocks that macOS hides.
- **Isolation:** READ COMMITTED. Conditional updates are safe under it; nothing needs SERIALIZABLE.

## Trade-offs

- Spring Data JDBC rewrites child collections when saving an aggregate. That is acceptable because order lines are written once; tracking history is appended through JdbcClient instead.
- One primary bounds write throughput. Architecture §16 shows the headroom at NFR-1; the scaling path is extraction, not sharding.

## Consequences

- Integration tests run on embedded PostgreSQL, without Docker.
- High-volume tables (outbox, processed messages, inbox, tracking events) get retention deletes in V1 and time partitioning if the deletes become costly.
