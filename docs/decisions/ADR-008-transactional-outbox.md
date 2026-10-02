# ADR-008: Transactional outbox with a polling relay

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [ADR-004](ADR-004-modular-monolith.md), [ADR-006](ADR-006-kafka.md), [ADR-007](ADR-007-saga-orchestration.md), [event model](../event-model.md)

## Context

Every state change here produces messages: internal commands and replies, integration events for Kafka, and jobs for the Job Scheduler. If the database commit and the message send happen separately, a crash between them either loses the message or sends one for a change that never committed.

## Problem

How are state changes and their messages made atomic, without distributed transactions?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Dual write: commit, then send | Simple | Loses or invents messages on crashes; ruled out |
| **Polling outbox**: messages inserted in the same transaction, a relay sends them | Atomic with the change; no extra infrastructure; easy to inspect and replay | Polling load and latency; the relay must preserve order |
| Outbox with change data capture (Debezium) | Low latency; no polling | A connector cluster to run and monitor; replication-slot management; only Kafka as a destination, while scheduler jobs and in-process commands need delivery too |
| Spring Modulith's event publication registry | Built in | Republication after failure does not preserve per-aggregate order; designed for in-process listeners, not commands or scheduler jobs; hides a mechanism this project exists to show |
| Event sourcing | The events are the state | A different persistence model for the whole system; out of proportion |

## Decision

**A polling outbox**, implemented in `platform` (proposed). CDC is not adopted merely because it is interesting; it is reconsidered if polling becomes a measured bottleneck.

- **One table** (`platform.outbox`): sequence (`bigserial`), `message_id`, destination (in-process module, Kafka topic, or scheduler), key, type, envelope, created and published timestamps, attempts.
- **Ordering:** rows are delivered per key in sequence order. Keys are hashed into lanes. Each lane is held by one worker at a time through a lease, so throughput scales with lanes while per-key order holds.
- **Latency:** a `NOTIFY` after commit wakes the relay at once; polling every 500 ms is the backstop.
- **Delivery:** at-least-once. A crash after sending but before marking resends the row, and consumers deduplicate on `message_id` ([ADR-010](ADR-010-idempotency.md)).
- **Destinations:** the in-process dispatcher for internal commands in V1 (Kafka from V2), Kafka for integration events, and the scheduler's REST API for jobs, with the row's `message_id` as its `Idempotency-Key`.
- **Retention:** published rows are deleted after 7 days by a recurring cleanup job.
- **Metrics:** pending rows and the age of the oldest row, per destination, with alerts.

## Trade-offs

- Polling adds load and up to 500 ms of latency when a `NOTIFY` is missed; batching keeps the load small.
- The outbox is a hot table at peak (about 3,000 rows/s). Retention deletes are cheap at this volume; time partitioning is the next step.
- One mechanism for three destinations is simpler to operate, but a slow destination (a scheduler outage) must not block the others, so lanes are per destination.

## Consequences

- No module ever sends a message directly; every message goes through the outbox in the same transaction as its cause.
- Kafka, scheduler and in-process delivery all share the same at-least-once and per-key-order semantics.

## Amendment (2026-10-02, phase 2 LLD)

Lanes and leases are replaced by an **eligibility rule**: a pending row may be delivered only when no earlier pending row exists for the same destination and aggregate, where earlier means a lower `(sequence, id)`. Workers claim eligible rows with `FOR UPDATE SKIP LOCKED`.

- **Order:** it follows the aggregate's version, which is assigned under its row lock, rather than identity values, which become visible in commit order rather than allocation order.
- **Parallelism:** any number of workers can deliver different aggregates in parallel, with no leases to manage.
- **Failures:** a failing or parked row blocks only its own aggregate, not a whole lane.

Details in [LLD §2.3](../low-level-design.md#23-ordering-per-aggregate-without-lanes-amends-adr-008).
