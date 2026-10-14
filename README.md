# E-Commerce Order System

The order-management backend of a direct-to-consumer store in India, built as an event-driven modular monolith with Java 25, Spring Boot, PostgreSQL, and Kafka. It demonstrates an order saga with compensation, stock reservations tested under contention, a transactional outbox, idempotent consumers, and observable eventual consistency.

It is one of four independent systems that integrate only through published contracts:

| System | Role for this system |
|---|---|
| [Payment-Orchestrator](https://github.com/Deepika-1201/Payment-Orchestrator) | Payment gateway. This system is one of its merchants: merchant API, hosted checkout, signed webhooks |
| [distributed-job-scheduler](https://github.com/Deepika-1201/distributed-job-scheduler) | Planned integration; the portfolio currently uses its own durable in-process task scheduler |
| [ride-hailing-platform](https://github.com/Deepika-1201/ride-hailing-platform) | Separate system in the same ecosystem; no direct integration |

> **Delivered:** catalogue, customers, carts, GST-inclusive pricing, inventory, ordering, payments, fulfillment, Kafka integration events, notifications, failure-recovery evidence, operational visibility, security gates, and bounded load tests. Start with the [portfolio guide](docs/portfolio-guide.md) or [recorded walkthrough transcript](docs/recordings/2026-10-15/transcript.txt). The [portfolio milestone](docs/portfolio-milestone.md) tracks its own evidence; the original [full roadmap](docs/implementation-plan.md) is preserved separately.

This is a backend portfolio, not a storefront or production deployment. The local default uses a fake payment adapter and a carrier simulator; an optional stack tests the real Payment Gateway against its mock PSPs. Service extraction, external scheduler integration, cloud deployment, distributed tracing/paging, and the 100,000-user flash-sale target remain future work.

## Portfolio milestone

**The six-item portfolio milestone is complete.** Its final implementation
[passed CI](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38620997376),
including the reviewer walkthrough and evidence checks. This does not complete
the larger production roadmap.

The smaller portfolio delivery target is tracked separately in
[docs/portfolio-milestone.md](docs/portfolio-milestone.md). The full phase roadmap
is preserved. P1 adds Kafka integration events, catalog availability hints, and
durable email notifications captured locally by Mailpit:

```bash
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-events.sh
```

Mailpit: <http://localhost:8025>. The demo does not send email to real recipients.
The milestone document records verification evidence, delivery limitations, and
the remaining portfolio work.

P2's selected failure evidence runs with `./gradlew portfolioFailureTest`.
P3 adds optional metrics, tested alerts, and a read-only operational dashboard:

```bash
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-observability.sh
```

Grafana: <http://localhost:3001/d/ecommerce-operations>. Prometheus:
<http://localhost:9090/alerts>. See [docs/portfolio-runbook.md](docs/portfolio-runbook.md)
for diagnosis and recovery. The overlay requires Docker Compose 2.24.4+ and
restricts the management port to localhost. These are local portfolio tools,
not a production paging or cross-service tracing deployment.

P4 enables bounded application rate limits, safe failure diagnostics, and CI
checks for packaged dependencies, the application image, and source secrets.
Quotas, scan commands, and deployment limits are in
[docs/portfolio-security.md](docs/portfolio-security.md).

P5 provides bounded k6 catalogue and checkout-contention workloads with database
invariant audits. Run `bash scripts/run-load.sh browse` or
`bash scripts/run-load.sh contention` against the local portfolio stack.
[docs/portfolio-performance.md](docs/portfolio-performance.md) contains measured
results, raw reports, exact reproduction commands, and their limitations.

For the single reviewer walkthrough, start the observability stack above and run
`bash scripts/demo-portfolio.sh`. Its clean-checkout rehearsal and real terminal
recording are linked from [docs/portfolio-guide.md](docs/portfolio-guide.md).

## Quick start

JDK 25 is required; Docker is optional.

```bash
./gradlew build          # compile (-Werror), module-boundary and architecture checks, all tests
./gradlew bootTestRun    # run locally on embedded PostgreSQL: API on :8080, health and info on :8081
./gradlew updateOpenApi  # after an API change: regenerate docs/api/openapi.json (the build fails while it is stale)
docker compose up --build   # or: the whole stack in containers, with JSON logs
```

`ECOM_ROLES` selects what an instance runs: `api`, `worker`, or both (the default).

### Local stack and demo

`docker compose up --build --detach` starts PostgreSQL, Keycloak on `:8180` (realm `ecommerce`, imported from [deploy/keycloak](deploy/keycloak/ecommerce-realm.json)), S3Proxy on `:9000` with the `ecommerce-media` bucket, and the app. Then:

```bash
scripts/demo-catalog.sh   # tokens, catalog admin, an image upload, anonymous browsing and search, addresses (curl and python3)
scripts/demo-cart.sh      # a guest cart with a coupon, merged at sign-in; quotes with GST checked to the paise; owner-only access
scripts/demo-inventory.sh # receipts and adjustments retried safely with Idempotency-Key; a SKU's movements; warehouse-only access
scripts/demo-orders.sh    # an order placed and retried safely, followed to AWAITING_PAYMENT, cancelled; one rejected for stock
```

Without the Payment Gateway, payments go to an in-memory fake that only tests drive. To run against the real gateway, built from [Payment-Orchestrator](https://github.com/Deepika-1201/Payment-Orchestrator) at a pinned commit with its mock PSPs, on `:8090` (Docker BuildKit needed):

```bash
export COMPOSE_FILE=docker-compose.yml:docker-compose.ecosystem.yml
docker compose up --build --detach   # also onboards this system as the gateway's merchant, once
scripts/demo-payments.sh  # pay on the hosted checkout, confirmed by webhook; cancel and refund; a PSP timeout, silence and decline
scripts/demo-fulfillment.sh  # packed and handed over, then delivered; scans out of order; returned to origin and refunded; a carrier outage; an unserviceable PIN code
```

Parcels go to a carrier simulator in the app. In the compose stacks it scans each parcel every 5 seconds once handed over; the last digit of the delivery PIN code picks its scenario ([LLD §8.9](docs/low-level-design.md#89-the-carrier-simulator)).

| User | Password | Role |
|---|---|---|
| `asha`, `ravi` | `asha-local-only`, `ravi-local-only` | `customer` |
| `admin` | `admin-local-only` | `admin` |
| `meera` | `meera-local-only` | `warehouse` |
| `sunita` | `sunita-local-only` | `support` |

These exist only in the local realm, whose `ecommerce-cli` client allows the password grant for scripts. To get a token: `curl -d grant_type=password -d client_id=ecommerce-cli -d username=asha -d password=asha-local-only localhost:8180/realms/ecommerce/protocol/openid-connect/token`. Keycloak imports the realm when its container is created: after a change to it, `docker compose up --detach --force-recreate keycloak`.

To use them with `./gradlew bootTestRun` instead of the app container, start only the dependencies: `docker compose up --detach keycloak s3proxy media-bucket`. The `local` profile points the app at them.

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
| [docs/low-level-design.md](docs/low-level-design.md) | Low-level design, one section per phase, written before its code |
| [docs/api.md](docs/api.md) | API conventions, error codes and endpoints |
| [docs/api/openapi.json](docs/api/openapi.json) | OpenAPI 3.1 document, generated from the code and checked by the build |
| [docs/database.md](docs/database.md) | Schemas and tables, per module |
| [docs/decisions/](docs/decisions/README.md) | Architecture decision records |
