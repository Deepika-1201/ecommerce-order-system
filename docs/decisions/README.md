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
| [ADR-007](ADR-007-saga-orchestration.md) | Orchestrated saga for the order lifecycle; payment success is the pivot; choreography for side reactions | Accepted, amended |
| [ADR-008](ADR-008-transactional-outbox.md) | Transactional outbox with a polling relay, for internal commands, Kafka events and scheduler jobs; per-aggregate ordering by an eligibility rule | Accepted, amended |
| [ADR-009](ADR-009-inventory-reservation.md) | Conditional updates and reservation records; hold expiry beyond the gateway's success window; flash-sale admission control | Accepted, amended |
| [ADR-010](ADR-010-idempotency.md) | Layered idempotency: API keys, domain uniqueness, message deduplication, outbound keys, guarded transitions | Accepted, extended |
| [ADR-011](ADR-011-event-versioning.md) | JSON Schema contracts in the repository, versioned event types; registry deferred | Accepted |
| [ADR-012](ADR-012-no-cache-v1.md) | No cache tier in V1 | Accepted |
| [ADR-013](ADR-013-technology-stack.md) | Java 25, Spring Boot 4.1, Spring Modulith, Spring for Apache Kafka, grpc-java, Keycloak, OpenTelemetry | Accepted, amended |
| [ADR-014](ADR-014-deployment.md) | AWS ap-south-1 on EKS, provisioned with Terraform; environments on demand | Accepted |
| [ADR-015](ADR-015-s3proxy-local-object-storage.md) | S3Proxy for local and test object storage, instead of the archived MinIO community edition | Accepted |
| [ADR-016](ADR-016-openapi-from-code.md) | The OpenAPI document is generated from code, committed and checked in CI | Accepted |
| [ADR-017](ADR-017-customer-resources-under-me.md) | Customer-owned resources are reached only through `/v1/me` | Accepted, extended |
| [ADR-018](ADR-018-gst-inclusive-prices.md) | GST is extracted per line from GST-inclusive prices, with the 2025 rates and slabs; shipping is taxed at the highest line rate | Accepted |
| [ADR-019](ADR-019-rounding-and-allocation.md) | Integer paise, half-up rounding per line and tax component, largest-remainder discount allocation; totals are sums | Accepted |
| [ADR-020](ADR-020-guest-cart-tokens.md) | Guest carts are opened by a secret cart token in a header, stored only as a hash | Accepted |
| [ADR-021](ADR-021-stock-movements.md) | Every change to on-hand stock writes a movement beside the counters; `on_hand` equals their sum | Accepted |
| [ADR-022](ADR-022-simulated-payments-and-fulfillment.md) | Payments and Fulfillment start as simulators behind their real messages, until phases 7 and 8 | Accepted |
| [ADR-023](ADR-023-order-address-snapshots.md) | Orders reference immutable address snapshots kept in the customer schema | Accepted |
| [ADR-024](ADR-024-tracking-newest-reachable-scan.md) | A shipment applies a carrier scan only if it is newer and its status reachable along the state machine | Accepted |
| [ADR-025](ADR-025-carrier-simulator.md) | The V1 carrier is a simulator in process, behind the carrier port, with its parcels in the database | Accepted |
| [ADR-026](ADR-026-serviceability-at-placement.md) | Placement checks that the carrier serves the delivery PIN code, from a list kept locally | Accepted |
