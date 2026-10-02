# Implementation plan

Builds the design in [architecture.md](architecture.md) incrementally, one vertical slice at a time.

**Working agreement (accepted 2026-10-02):**

- Each phase's low-level design is written in `low-level-design.md` **before** its code.
- A phase is done when:
  - its exit criteria are met;
  - unit and integration tests are green in CI;
  - the docs and ADRs reflect what changed;
  - the work is committed and pushed.

## Phases

| # | Phase | Scope | Exit criteria | Status |
|---|---|---|---|---|
| 0 | Design | Requirements, domain model, order lifecycle, HLD, consistency, failure handling, event model, ADR-001 to ADR-014 | Architecture approved | In review |
| 1 | Scaffolding | Gradle (Kotlin DSL), Java 25, Spring Boot 4.1; module skeletons with a boundary test; configuration; health and readiness; JSON logs; problem+json errors; container image; `docker compose` (PostgreSQL, Kafka, Keycloak, Mailpit, MinIO, Grafana LGTM); GitHub Actions | `./gradlew build` green in CI; the app starts in both roles; a forbidden module dependency fails the build | |
| 2 | Platform | Outbox with lanes, dispatcher and relay (`NOTIFY` plus polling); processed messages; idempotency keys; audit log; `TaskScheduler` port with its in-process adapter | Crash-between-steps tests: no lost and no duplicated effects | |
| 3 | Catalog and customers | Products, variants, categories, prices, image upload through pre-signed URLs; customer profiles and addresses; OIDC resource server and roles | API tests, including cross-customer access being refused | |
| 4 | Cart and pricing | Carts (guest carts and merge at sign-in); quotes with GST, shipping fees and rounding; coupons with a redemption limit | Property tests: totals equal the sum of their parts; rounding rules hold | |
| 5 | Inventory | Stock, reservations, conditional updates, hold expiry and reclamation, receipts and adjustments | Concurrency tests: N parallel attempts on the last unit give exactly one success; availability never goes negative | |
| 6 | Ordering and saga | Order and OrderProcess; placement with `Idempotency-Key`; the saga with fake payment and carrier ports; deadlines and the sweep; cancellation from every state | Every transition and compensation tested, including late and duplicate replies; invalid transitions rejected | |
| 7 | Payments | Gateway adapter, hosted checkout, webhooks (signature, inbox, versions), polling backstop, refunds, late success; contract tests against the gateway's OpenAPI; `ecosystem` compose profile | End to end against the real gateway with its mock PSP scenarios: decline, timeout, pending without a webhook | |
| 8 | Fulfillment | Shipments, carrier simulator with PIN-code scenarios, tracking webhooks (out of order), handover, return to origin | Tracking never moves backwards; return to origin restocks and refunds | |
| 9 | Events and notifications | Integration events on Kafka, event schemas and compatibility tests, the notifications consumer with dead-letter topic, catalog availability hints | Duplicate and poison-message tests pass | |
| 10 | Scheduler integration | Job submission through the outbox; Java gRPC worker (sessions, heartbeats, self-fencing); recurring sweeps, reconciliation and cleanup | Integration tests against the real scheduler image, including a worker crash | |
| 11 | Failure testing | Scenarios S1–S20 from [failure-handling.md](failure-handling.md) | Every scenario automated, or documented as manual with the reason | |
| 12 | Observability | Metrics (brief §38), traces across gateway and scheduler, dashboards, alert rules tested with `promtool`, runbooks; `observability.md` | An order traceable end to end | |
| 13 | Security hardening | Rate limits, guest links, PII masking, secrets, audit, OWASP checklist; `security.md` | Security tests pass | |
| 14 | V2: internal messages over Kafka | Commands and replies move from the in-process dispatcher to Kafka topics | The phase 6 saga tests pass unchanged over Kafka | |
| 15 | V3: Inventory as a service | Inventory extracted with its own database and deployment; the saga runs across services | Saga and failure tests pass across services | |
| 16 | Flash sale and load tests | Waiting room, sold-out short-circuit; k6 scenarios: normal, peak, flash sale, spike | NFR-1 and NFR-2 met, or the architecture revisited with evidence | |
| 17 | Deployment | Terraform (AWS ap-south-1: EKS, RDS, MSK, S3), manifests, CI/CD, cost estimate; `deployment.md` | An environment created and destroyed from CI (account permitting) | |
| 18 | Production readiness review | The brief's §55 checklist; `design.md` consolidating the final design | Review passed | |

**Delivery progression:**

| Version | Phases |
|---|---|
| V1 | 1–13 |
| V2 | 14 |
| V3 and V4 | 15 |
| V5 | 11 and 16 |
| V6 | 17–18 |

## Documents still to come

| Document | Written in |
|---|---|
| `low-level-design.md` | One section per phase, before its code |
| `api.md` and the OpenAPI document | Grows from phase 3; complete by phase 9 |
| `database.md` | Grows from phase 2 with each module's schema |
| `observability.md` | Phase 12 |
| `security.md` | Phase 13 |
| `deployment.md` | Phase 17 |
| `design.md` | Phase 18: the consolidated design the brief asks for |
