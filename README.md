# E-Commerce Order System

The order-management backend of a direct-to-consumer store in India, built as an event-driven distributed system. It covers an order saga with compensation, inventory reservation that never oversells (including in a flash sale), a transactional outbox feeding broker events, and eventual consistency that customers can see.

It is one of four independent systems that integrate only through published contracts:

| System | Role for this system |
|---|---|
| [Payment-Orchestrator](https://github.com/Deepika-1201/Payment-Orchestrator) | Payment gateway. This system is one of its merchants: merchant API, hosted checkout, signed webhooks |
| [distributed-job-scheduler](https://github.com/Deepika-1201/distributed-job-scheduler) | Runs this system's deadlines, retried calls and recurring jobs |
| [ride-hailing-platform](https://github.com/Deepika-1201/ride-hailing-platform) | Separate system in the same ecosystem; no direct integration |

> **Status:** Phase 0, design. Requirements are approved; the architecture (HLD, saga, consistency and failure analysis, ADR-004 to ADR-014) is in review. There is no code yet: implementation starts once the architecture is approved.

## Documentation

| Doc | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Scope, ecosystem context, functional and non-functional requirements |
| [docs/domain-model.md](docs/domain-model.md) | Bounded contexts, aggregates, invariants, commands and events |
| [docs/order-lifecycle.md](docs/order-lifecycle.md) | Order, reservation and shipment state machines; races; recovery |
| [docs/architecture.md](docs/architecture.md) | High-level design: components, data flows, saga, scalability, flash sale, security, observability, deployment |
| [docs/consistency-model.md](docs/consistency-model.md) | What is strongly and what is eventually consistent, with staleness bounds |
| [docs/failure-handling.md](docs/failure-handling.md) | 20 failure scenarios: detection, recovery, compensation, duplicates, customer impact |
| [docs/event-model.md](docs/event-model.md) | Commands, replies and events; envelope; topics; ordering; deduplication |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases and exit criteria |
| [docs/decisions/](docs/decisions/README.md) | Architecture decision records |
