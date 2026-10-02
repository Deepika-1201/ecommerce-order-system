# ADR-004: Modular monolith first, with api and worker roles

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [Architecture §9](../architecture.md#9-high-level-architecture), [domain model](../domain-model.md), [ADR-007](ADR-007-saga-orchestration.md), [ADR-008](ADR-008-transactional-outbox.md)

## Context

- Nine bounded contexts ([domain model](../domain-model.md)); peak load of 200 orders/s (NFR-1); one developer.
- The brief asks for a system that becomes more distributed step by step (V1 monolith → V3 extraction) and warns against microservices for their own sake.
- The payment and shipping steps already cross process boundaries in V1, so the hard distributed problems exist from day one regardless of how this system is packaged.

## Problem

How is V1 packaged and structured, and how is extraction into services kept cheap?

## Options considered

| Criterion | A. Modular monolith | B. A few services (storefront, orders, inventory, fulfillment) | C. A service per context (9) |
|---|---|---|---|
| Development complexity | Lowest: one build, one debugger | Medium | Highest |
| Deployment complexity | One image | Four images, versioned contracts | Nine images |
| Data ownership | Schema per module, enforced by tests | Database per service | Database per service |
| Transactions | Local inside a module; messages between modules | Messages between services | Messages everywhere, even for trivial reads |
| Scalability | Roles scale separately; modules don't | Per service | Per service |
| Debugging | One process, one trace | Distributed traces | Distributed traces across nine hops |
| Operational burden | Lowest | Medium | Highest |
| Learning value | Boundaries and messaging, without the operational noise | Real extraction | Mostly operational noise at this scale |

## Decision

**A, modular monolith** (proposed), with rules that keep B one step away:

1. **One deployable, two roles.** `api` serves HTTP. `worker` runs the dispatcher, relay, Kafka consumers, scheduler worker and outbound calls. Locally one process runs both.
2. **One module per context**, plus `platform` (messaging, idempotency, tasks, audit) and `shared` (money, ids, errors). Each module owns a database schema: no joins, foreign keys or transactions across schemas.
3. **Between modules:** read-only queries go through a module's published Java API; state changes go only through commands and events in the outbox, even in-process ([ADR-008](ADR-008-transactional-outbox.md)). The saga therefore behaves the same in V1 as it will across services.
4. **Boundaries verified in tests:** Spring Modulith's module verification and ArchUnit rules fail the build on a forbidden dependency.
5. **Extraction path:** V2 moves the internal channels to Kafka within the same deployable. V3 extracts Inventory first, because it scales differently in a flash sale.

## Trade-offs

- Asynchronous steps inside one process cost latency (a few hundred milliseconds per checkout) and a dispatcher, compared with a local transaction. In exchange, failure handling is real from day one and extraction changes the transport, not the logic.
- One deployable is one blast radius in V1. A bad release affects browsing and checkout together.
- Boundaries hold only as long as the tests enforce them.

## Consequences

- Order placement answers `202` and the client follows the order's status (Q7).
- The checkout saga, its compensations and its failure tests are written once and reused in V2 and V3.
- Module APIs are designed as if remote: coarse-grained, versionable, with no shared entities.
