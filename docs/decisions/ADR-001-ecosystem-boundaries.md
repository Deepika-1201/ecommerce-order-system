# ADR-001: Four independent systems on shared infrastructure

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [requirements §2](../requirements.md#2-ecosystem-context), [ADR-002](ADR-002-payment-gateway-integration.md), [ADR-003](ADR-003-job-scheduler-integration.md)

## Context

The stakeholder's ecosystem architecture places four portfolio systems behind one API gateway, above one event broker and one set of shared infrastructure (PostgreSQL, Redis, object storage, observability):

| System | State on 2026-10-02 |
|---|---|
| Distributed Job Scheduler (Go) | Built. PostgreSQL-backed dispatch, gRPC workers, no broker (its ADR-003) |
| Payment Gateway (Java/Spring Boot) | Built. Merchant REST API, hosted checkout, signed webhooks, no broker (its ADR-004) |
| Ride-hailing | Not started |
| E-commerce (this project) | Phase 1 |

The brief that accompanies the diagram describes the four as "independent systems that share architectural patterns", not one giant system. The diagram also shows runtime links: e-commerce calls the payment gateway, every system publishes to the broker, and the broker feeds the scheduler.

Today the two built systems cannot run side by side: both `docker compose` files bind host ports 5432 and 8080, and both observability stacks use 3000 and 9090.

## Problem

At runtime, are the four one system or four? What may they share, how do they integrate, and who owns the shared parts?

## Options considered

| Option | Coupling | Failure isolation | Learning value | Effort |
|---|---|---|---|---|
| A. One integrated system: shared database or shared code | High: schema changes ripple across systems | None: one bad query or deploy affects all four | Low: hides the integration problems the projects exist to show | Low at first, high later |
| **B. Independent systems that integrate through published contracts, on shared infrastructure** | Contracts only | Per system; shared servers are the remaining risk, mitigated by rule 3 | High: real cross-system failures (timeouts, unknown outcomes, duplicate webhooks, contract versioning) | Medium: contracts and contract tests |
| C. Fully isolated systems with no integration | None | Total | Medium: each system alone, no cross-system story | Low |

## Decision

**Option B** (proposed).

1. **Ownership.** Each system has its own repository, database, database role, deployment and release cycle. No system reads another system's database, even on a shared server.
2. **Contracts.** Systems integrate only through REST APIs described by OpenAPI, signed webhooks, broker events with versioned schemas, and the scheduler's job API. Each system runs alone with fakes for its neighbors, and contract tests keep the fakes honest.
3. **Shared servers, not shared data.**

   | Resource | Locally | In the cloud |
   |---|---|---|
   | PostgreSQL | One server; a database and a role per system | Separate instances, at least for the gateway and this system: a flash sale here must not add latency to payments |
   | Redis | One server; an ACL user per system, restricted to its key prefix | Separate instances where used |
   | Object storage | One MinIO server; a bucket per system | A bucket per system |

   Redis is first justified by ride-hailing (geo indexes) and edge rate limiting. This system uses it only if a later ADR justifies it.
4. **Events and tasks.** The broker carries facts ("an order was placed"), fanned out and replayable. The scheduler carries tasks ("do this at time T, retry with backoff, dead-letter for re-drive"). Since the scheduler already covers task queues, the broker's role is an event log, which leans toward Kafka over RabbitMQ. The product is chosen in its own ADR at Technology Selection.
5. **Edge.** One API gateway for external traffic: TLS termination, routing, OIDC token validation for end-user routes, coarse per-client rate limits. The auth policy is set per route: OIDC for customer and staff APIs; pass-through for signed webhooks, merchant API keys and the gateway's checkout URLs. Each system still authenticates and authorizes every request, and domain admission control (such as a flash-sale waiting room) stays in the system that owns the domain. Calls between systems use the internal network and each system's own credentials, not the public edge. The exception is the gateway's webhooks: outside local runs, its SSRF guard (`pg.webhooks.outbound.allow-private-targets=false`) refuses private addresses, so they reach this system through the edge, authenticated by their signature.
6. **Observability.** One stack (OpenTelemetry collector, Prometheus, Grafana, Tempo, Loki) for all systems. Business ids cross system boundaries: the gateway's `merchant_order_id` is the order id, and scheduler jobs carry the order id as `correlation_id`.
7. **Scope of this repository.** It builds only the e-commerce system and requires no change to the sibling projects (confirmed 2026-10-02). Ecosystem-wide assets (edge gateway configuration, identity-provider realm, broker ACLs, one observability stack for all systems, a compose file for all four, shared Terraform) belong in a platform repository, deferred until a second system needs them. Until then, this repository's compose file runs what this system needs, and an optional profile runs the real gateway and scheduler on their own host ports.

## Trade-offs

- Several repositories, and the contracts between them, must be kept in step. Contract tests and versioned schemas are the price of independent releases.
- The full local stack (four systems plus infrastructure) is heavy. Standalone mode with fakes keeps day-to-day development light.
- Shared servers are still shared failure domains locally. That is acceptable for development, not for production payments.
- Improvements in sibling projects would simplify this one (an HTTP executor in the scheduler, a merchant listing API in the gateway), but none is required. Any such change gets an ADR in the repository that owns it.

## Consequences

- This system ships fakes for the gateway and the scheduler, plus contract tests against their published contracts: the gateway's `docs/openapi.yaml` and the scheduler's `api/openapi.yaml`.
- Ride-hailing can join the same way, as the gateway's second merchant and the scheduler's second tenant.
- The broker product, edge gateway product and container runtime are chosen at this project's Technology Selection, and reused if the platform repository is created.
