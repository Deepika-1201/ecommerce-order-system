# Architecture Decision Records

Format: Context → Problem → Options considered → Decision → Trade-offs → Consequences. One decision per file. Status values: Proposed, Accepted, Superseded.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-ecosystem-boundaries.md) | Four independent systems on shared infrastructure; integration only through published contracts; this repository builds only the e-commerce system | Accepted |
| [ADR-002](ADR-002-payment-gateway-integration.md) | Payments through the ecosystem's Payment Gateway, as a merchant | Accepted |
| [ADR-003](ADR-003-job-scheduler-integration.md) | Background tasks on the ecosystem's Job Scheduler, run by a Java worker in this system; deadlines as recurring sweeps | Accepted, amended |
| [ADR-004](ADR-004-modular-monolith.md) | Modular monolith first, with api and worker roles; modules interact through messages even in-process | Accepted |
| [ADR-005](ADR-005-postgresql.md) | PostgreSQL 17, a schema per module, Flyway, Spring Data JDBC and JdbcClient | Accepted |
| [ADR-006](ADR-006-kafka.md) | Kafka as the event broker | Accepted |
| [ADR-007](ADR-007-saga-orchestration.md) | Orchestrated saga for the order lifecycle; payment success is the pivot; choreography for side reactions | Accepted |
| [ADR-008](ADR-008-transactional-outbox.md) | Transactional outbox with a polling relay, for internal commands, Kafka events and scheduler jobs | Accepted |
| [ADR-009](ADR-009-inventory-reservation.md) | Conditional updates and reservation records; hold expiry beyond the gateway's success window; flash-sale admission control | Accepted |
| [ADR-010](ADR-010-idempotency.md) | Layered idempotency: API keys, domain uniqueness, message deduplication, outbound keys, guarded transitions | Accepted |
| [ADR-011](ADR-011-event-versioning.md) | JSON Schema contracts in the repository, versioned event types; registry deferred | Accepted |
| [ADR-012](ADR-012-no-cache-v1.md) | No cache tier in V1 | Accepted |
| [ADR-013](ADR-013-technology-stack.md) | Java 25, Spring Boot 4.1, Spring Modulith, Spring for Apache Kafka, grpc-java, Keycloak, OpenTelemetry | Accepted |
| [ADR-014](ADR-014-deployment.md) | AWS ap-south-1 on EKS, provisioned with Terraform; environments on demand | Accepted |
