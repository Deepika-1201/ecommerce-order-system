# E-Commerce Order System

The order-management backend of a direct-to-consumer store in India, built as an event-driven distributed system. It covers an order saga with compensation, inventory reservation that never oversells (including in a flash sale), a transactional outbox feeding broker events, and eventual consistency that customers can see.

It is one of four independent systems that integrate only through published contracts:

| System | Role for this system |
|---|---|
| [Payment-Orchestrator](https://github.com/Deepika-1201/Payment-Orchestrator) | Payment gateway. This system is one of its merchants: merchant API, hosted checkout, signed webhooks |
| [distributed-job-scheduler](https://github.com/Deepika-1201/distributed-job-scheduler) | Runs this system's deadlines, retried calls and recurring jobs |
| [ride-hailing-platform](https://github.com/Deepika-1201/ride-hailing-platform) | Separate system in the same ecosystem; no direct integration |

> **Status:** Phases 1 (scaffolding), 2 (platform: transactional outbox, dispatcher, Kafka relay, task scheduler, idempotency keys, audit log), 3 (catalog and customers: OIDC resource server, catalog with search and pre-signed image uploads, customer profiles and addresses, OpenAPI document), 4 (cart and pricing: guest and customer carts with merge at sign-in, quotes with GST and shipping, coupons with redemption limits), 5 (inventory: reservations that never oversell, hold expiry with on-demand reclaim, warehouse receipts and adjustments with a movement ledger) and 6 (ordering: orders placed from a quote with an Idempotency-Key, the order saga across Inventory, Pricing and, as simulators until phases 7 and 8, Payments and Fulfillment, with compensation, late and duplicate replies, cancellation by customers and support, and deadlines) are done. Phase 7 (payments: the gateway adapter, hosted checkout, webhooks and refunds) is next. The design (requirements, HLD, saga, consistency and failure analysis, ADR-001 to ADR-014) was approved on 2026-10-02.

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
