# Portfolio reviewer guide

## What this project demonstrates

An event-driven modular monolith for order management. PostgreSQL transactions
protect each module's durable state; a saga coordinates inventory, coupons,
payments, cancellation, and fulfillment. A transactional outbox carries internal
commands and publishes integration events to Kafka. Consumers commit their
database effects before acknowledging broker offsets.

This repository contains the e-commerce backend, not a storefront, payment
processor, real carrier, or cloud deployment. Local payments use a fake adapter;
the optional ecosystem stack integrates the independent Payment Gateway with
its mock PSPs. The carrier remains a scenario-driven simulator.

## Start locally

Prerequisites: Docker with Compose 2.24.4+, Bash, curl, and Python 3 for the demo
scripts. JDK 25 is needed for host-side Java tests; Node 18+ is needed for load
reports and security-report checks. No cloud account or sibling checkout is
required for the default portfolio stack.

```sh
git clone https://github.com/Deepika-1201/ecommerce-order-system.git
cd ecommerce-order-system
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-portfolio.sh
```

Use an unused set of the documented local ports; do not start a second copy over
an existing stack. The tested local environment is an Apple M2 with 8 GiB host
memory and a four-CPU, approximately four-GiB Docker VM. Startup downloads images
and dependencies; it is not part of the short demonstration time.
With Colima, put the checkout in a Docker-shared directory such as your home
directory. An unshared macOS temporary directory cannot supply the compose bind
mounts for Prometheus, Grafana, and the identity realm.

| Surface | Local URL |
|---|---|
| API | <http://localhost:8080> |
| Management/OpenAPI | <http://localhost:8081/actuator/openapi> |
| Grafana, read-only | <http://localhost:3001/d/ecommerce-operations> |
| Prometheus alerts | <http://localhost:9090/alerts> |
| Captured email | <http://localhost:8025> |

The Keycloak accounts and secrets in compose are explicit local fixtures. Do not
use real customer information or credentials in this demonstration. The default
Mailpit service captures email locally rather than delivering it externally.
The walkthrough wrapper composes the existing event and observability demos;
CI runs that same command. Individual demo scripts remain available below.

## Short walkthrough

1. Explain the boundary: one modular application, a shared PostgreSQL instance
   with module-owned schemas, durable internal tasks, and Kafka integration
   events. Use [the architecture](architecture.md) and
   [module-boundary decision](decisions/ADR-004-modular-monolith.md).
2. Run `bash scripts/demo-events.sh`. It receives stock, observes IN_STOCK, places
   an order, replays the identical Idempotency-Key request, observes SOLD_OUT,
   cancels, and verifies that stock becomes available again. Mailpit captures
   placed/cancelled notifications for that order.
3. Open Mailpit and the Grafana dashboard. Run
   `bash scripts/demo-observability.sh` to demonstrate a stock rejection,
   correlated committed-order logs, real metric samples, and loaded alerts.
4. Explain one failure boundary: a crash after broker send can resend the same
   message, but its inbox marker and database effect commit atomically. A failed
   DLT write leaves the source offset uncommitted. The
   [P2 evidence matrix](portfolio-milestone.md#failure-evidence-matrix) names the
   executable checks.
5. Show [the performance evidence](portfolio-performance.md), including its
   slower contention run, two-customer setup, small dataset, and absence of a
   maximum-capacity claim. Close with the explicit future-work list below.

The architecture documents include future-stage designs. Use this guide and the
portfolio checklist to distinguish those targets from the implemented footprint.

## Recorded rehearsal

An actual 41.1-second terminal recording was captured from public commit
`163b14a` in a clean clone, using new compose volumes. It runs the event and
observability scripts directly, which the new wrapper composes. Before the run
there were zero products, orders, and notifications; afterward there were two
products, three orders, and six notification intents.

- [Read the actual transcript](recordings/2026-10-15/transcript.txt).
- [Download the terminal recording](recordings/2026-10-15/walkthrough.cast).
- [Inspect the clean-checkout evidence](recordings/2026-10-15/fresh-checkout.json).

Play the terminal recording with asciinema, installed separately:

```sh
asciinema play docs/recordings/2026-10-15/walkthrough.cast
```

This is a real terminal recording, not a narrated video or a recording of the
Grafana/Mailpit browser screens. The transcript is derived from the same output;
CI verifies its hash, timestamps, success markers, and matching transcript.
No stdin or bearer tokens were recorded. Names and checkout references in the
output belong to synthetic local fixtures and the fake gateway.

The checkout and data were fresh, but Docker/build caches could be reused. This
was not a cold-download timing measurement. A machine-specific Docker build
workaround supplied alternate GitHub release-asset IPs; the normal CI build needs
no such override. The first local attempt used an unshared macOS temporary
directory and failed to bind configuration files; retrying under the shared home
directory passed. Both attempts restored the original containers and volumes;
only the rehearsal's newly created resources were removed.

## Claims and evidence

| Claim | Where to inspect |
|---|---|
| Stock reservations remain consistent under contention | [LastUnitTests](../src/test/java/com/ecommerce/inventory/LastUnitTests.java), [stock design](low-level-design.md#5-inventory-phase-5), and [measured SQL audits](portfolio-performance.md#contention-results) |
| Checkout retries do not create another order | [PlacementTests](../src/test/java/com/ecommerce/ordering/PlacementTests.java), [idempotency ADR](decisions/ADR-010-idempotency.md), and the event demo |
| Late and duplicated replies do not corrupt the saga | [LateReplyTests](../src/test/java/com/ecommerce/ordering/LateReplyTests.java), [CancellationTests](../src/test/java/com/ecommerce/ordering/CancellationTests.java), and [order lifecycle](order-lifecycle.md) |
| Broker outages and consumer crash windows are recoverable | [P2 matrix](portfolio-milestone.md#failure-evidence-matrix), `./gradlew portfolioFailureTest` |
| Notifications are durable, not exactly-once email | [NotificationKafkaTests](../src/test/java/com/ecommerce/notifications/domain/NotificationKafkaTests.java), [SMTP decision](decisions/ADR-028-durable-email-notifications.md) |
| Order flows can be diagnosed | [Runbook](portfolio-runbook.md), provisioned dashboard, [observability decision](decisions/ADR-029-portfolio-observability.md) |
| Security controls have executable gates | [Security scope](portfolio-security.md), rate-limit HTTP tests, jar/image/secret checks in CI |
| Performance numbers are measured and bounded | [Results and raw reports](portfolio-performance.md), `bash scripts/run-load.sh browse`, `bash scripts/run-load.sh contention` |
| Real gateway integration exists | [Ecosystem setup](../README.md#local-stack-and-demo), payment/fulfillment demos, and CI's ecosystem job |

## Design choices worth discussing

- [Orchestrated saga](decisions/ADR-007-saga-orchestration.md): compensations and
  late replies are explicit; the business workflow is not a distributed ACID
  transaction. Payment success is the pivot.
- [Transactional outbox](decisions/ADR-008-transactional-outbox.md): durable intent
  survives failures, while at-least-once publication requires idempotent consumers.
- [Stock reservation](decisions/ADR-009-inventory-reservation.md): availability is
  decided by Inventory's transaction, not the eventually consistent catalogue hint.
- [Stock event keys](decisions/ADR-027-stock-event-keys.md): SKU and location have
  independent versions, so Kafka compaction must preserve both.
- [Inclusive GST and allocation](decisions/ADR-018-gst-inclusive-prices.md): integer
  paise and explicit rounding/allocation rules keep totals explainable.
- [Local rate limits](decisions/ADR-030-application-rate-limits.md): bounded safety
  controls are useful here but are not a distributed edge quota or bot defense.

## Verification and limits

`./gradlew build` runs compiler warnings-as-errors, architecture/module checks,
unit/integration/property tests, and the committed OpenAPI comparison. CI also
runs alert-rule tests, secret and artifact vulnerability gates, fresh compose
demos, load smoke checks, and the real-gateway ecosystem tests. Inspect the run
for the current commit rather than an older green badge.

Deferred: real scheduler integration, internal saga transport over Kafka,
Inventory service extraction, a waiting room and the full flash-sale target,
AWS deployment, cross-service traces, external paging, and formal production
readiness. The [original roadmap](implementation-plan.md) remains the record of
that larger scope; the [portfolio checklist](portfolio-milestone.md) is separate.

Stopping the local demo with `docker compose stop` preserves its data. Use
`docker compose down --volumes` only for a disposable project whose data you mean
to delete; the fresh-checkout rehearsal must never remove an existing stack's
volumes.