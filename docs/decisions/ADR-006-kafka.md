# ADR-006: Kafka as the event broker

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [ADR-001](ADR-001-ecosystem-boundaries.md) (rule 4: the broker carries facts, the scheduler carries tasks), [ADR-008](ADR-008-transactional-outbox.md), [event model](../event-model.md)

## Context

- The ecosystem has a shared broker ([ADR-001](ADR-001-ecosystem-boundaries.md)). Its product was left to Technology Selection; the stakeholder's diagram says "Kafka / RabbitMQ".
- Broker traffic here is **facts**: order and stock events with several independent consumers (notifications, availability hints; later search, analytics, other systems). From V2, the internal commands and replies travel on it too.
- **Tasks** (do X at time T, with retries) already go to the Job Scheduler.
- Both sibling projects run without a broker, so broker semantics are the gap this project fills in the portfolio. That is a stated driver of this decision, not a hidden one.

## Problem

Which broker carries events, and later internal commands, with per-aggregate ordering, fan-out and replay?

## Options considered

| Criterion | **Kafka** | RabbitMQ | SNS + SQS | Redis Streams | PostgreSQL only |
|---|---|---|---|---|---|
| Ordering | Per partition (key) | Per queue; lost with competing consumers or redelivery | FIFO per message group, with throughput limits | Per stream | Per table |
| Replay and retention | Retention by time or size; replay from any offset; compaction | Gone once acknowledged (streams aside) | No replay | Until trimmed; bounded by memory | Whatever is built |
| Fan-out | Consumer groups with independent offsets | Exchanges and queues | A queue per subscriber | Consumer groups | Built by hand |
| Delivery | At-least-once (exactly-once only inside Kafka) | At-least-once | At-least-once | At-least-once | At-least-once |
| Operations | Heaviest; simpler since KRaft | Moderate | None (managed) | Light; durability depends on persistence settings | None |
| Local development | Single-node KRaft container; embedded for tests | Container | LocalStack | Container | Already there |
| Cloud cost when idle | MSK from about $100/month | A broker instance billed hourly | About zero | A cache node billed hourly | Zero |

## Decision

**Kafka** (proposed), Apache Kafka 4.x in KRaft mode.

1. Events are facts with several independent consumers and a need to replay, for example to rebuild a projection or add a consumer later. Kafka's model fits that directly.
2. The scheduler already covers task-queue semantics (delays, retries, dead-lettering), which removes RabbitMQ's main advantage here.
3. Per-key ordering matches per-aggregate sequences.
4. It fills the portfolio's missing piece: partitions, consumer groups, rebalancing, redelivery, dead-letter topics and schema evolution.

**Configuration:**

- **Producers:** `acks=all` with idempotence enabled.
- **Topics:** replication 3 and `min.insync.replicas` 2 in the cloud.
- **Consumers:** commit offsets only after their database transaction commits, and deduplicate through `processed_messages`.

**Where it runs:**

- Locally: one KRaft container.
- Tests: embedded Kafka from `spring-kafka-test`, so no Docker is needed.
- AWS: MSK Serverless, provisioned MSK or Strimzi on EKS, chosen in phase 17 with a cost estimate.

## Trade-offs

- Heavier to run than V1's volume strictly requires; on-demand environments keep the cloud cost bounded.
- Exactly-once semantics in Kafka (transactions) do not cover database side effects, so they are not used. Deduplication in the consumer's transaction gives effectively-once processing without claiming exactly-once delivery.
- Long retry delays do not belong in Kafka consumers. Slow work goes to the scheduler instead.

## Consequences

- Topics, keys and dead-letter conventions are defined in the [event model](../event-model.md).
- Consumer lag and dead-letter topics are monitored and alerted.
- The ecosystem's other systems can adopt the same cluster and envelope when a platform repository exists ([ADR-001](ADR-001-ecosystem-boundaries.md)).
