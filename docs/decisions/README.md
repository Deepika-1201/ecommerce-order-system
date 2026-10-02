# Architecture Decision Records

Format: Context → Problem → Options considered → Decision → Trade-offs → Consequences. One decision per file. Status values: Proposed, Accepted, Superseded.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-ecosystem-boundaries.md) | Four independent systems on a shared platform; integration only through published contracts | Proposed |
| [ADR-002](ADR-002-payment-gateway-integration.md) | Payments through the ecosystem's Payment Gateway, as a merchant | Proposed |
| [ADR-003](ADR-003-job-scheduler-integration.md) | Background tasks on the ecosystem's Job Scheduler, through an HTTP executor | Proposed |

Planned, from the brief: modular monolith vs. services, database, broker, saga orchestration vs. choreography, outbox, inventory reservation, event versioning, idempotency, caching, deployment.
