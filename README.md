# E-Commerce Order System

The order-management backend of a direct-to-consumer store in India, built as an event-driven distributed system. It covers an order saga with compensation, inventory reservation that never oversells (including in a flash sale), a transactional outbox feeding broker events, and eventual consistency that customers can see.

It is one of four independent systems that integrate only through published contracts:

| System | Role for this system |
|---|---|
| [Payment-Orchestrator](https://github.com/Deepika-1201/Payment-Orchestrator) | Payment gateway. This system is one of its merchants: merchant API, hosted checkout, signed webhooks |
| [distributed-job-scheduler](https://github.com/Deepika-1201/distributed-job-scheduler) | Runs this system's deadlines, retried calls and recurring jobs |
| [ride-hailing-platform](https://github.com/Deepika-1201/ride-hailing-platform) | Separate system in the same ecosystem; no direct integration |

> **Status:** Phase 1, requirements discovery. There is no code yet: implementation starts once the architecture has been reviewed and approved.

## Documentation

| Doc | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Scope, ecosystem context, functional and non-functional requirements, open items |
| [docs/decisions/](docs/decisions/README.md) | Architecture decision records |
