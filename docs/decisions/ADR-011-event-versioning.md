# ADR-011: Event contracts and versioning

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [Event model](../event-model.md), [ADR-006](ADR-006-kafka.md)

## Context

Integration events are a public contract: consumers deploy on their own schedule and may lag behind producers. Internal commands become Kafka messages in V2 and cross service boundaries in V3. Today there are two consumers, both inside this system; other systems may subscribe later.

## Problem

How are message schemas defined and evolved without breaking consumers running older versions?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| No schemas: implicit JSON | Nothing to maintain | Breaks silently |
| **JSON with JSON Schema in the repository**, compatibility-tested in CI | Readable messages; schemas reviewed with the code; no extra infrastructure | Compatibility is only as good as the tests |
| Avro or Protobuf with a schema registry (Confluent, Apicurio) | The registry enforces compatibility when a schema is registered; compact | A registry to run; binary messages are harder to inspect; worth it with many teams or systems |

## Decision

**JSON with JSON Schema in the repository** (proposed). A schema registry is deferred until another system consumes these topics.

- **Envelope:** the one in the [event model §2](../event-model.md#2-envelope), the same for every message.
- **Type names carry a major version**, for example `order.confirmed` version 1. One schema per type and version, under `contracts/events/`.
- **Within a version**, only additive, optional changes are allowed. Consumers ignore unknown fields (tolerant reader) and never depend on field order.
- **A breaking change** creates version N+1, published alongside version N until every consumer has moved, then N is retired.
- **CI checks** every schema change against stored sample messages of earlier versions, and validates produced messages against their schema in tests.
- **Personal data** never enters event payloads, so schemas never need to change for privacy reasons.

## Trade-offs

- No runtime enforcement: a producer bug could publish an invalid message. Tests in CI and dead-letter topics at consumers contain it.
- Publishing two versions during a migration costs extra messages for a while.

## Consequences

- Phase 9 adds the schemas and the compatibility tests. Phase 14 (V2) applies the same rules to commands and replies.
- When the platform repository and a second consuming system arrive, the schemas move to a registry without changing the envelope.
