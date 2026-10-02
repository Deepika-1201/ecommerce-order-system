# Low-level design

| | |
|---|---|
| Status | Grows one section per phase. Each section is written before its phase's code ([implementation plan](implementation-plan.md)) |
| Inputs | [Architecture](architecture.md) · [Domain model](domain-model.md) · [Decisions](decisions/README.md) |

## 1. Scaffolding (phase 1)

### 1.1 Repository layout

```
build.gradle.kts, settings.gradle.kts, gradlew, gradle/wrapper/
Dockerfile, docker-compose.yml, .github/workflows/ci.yml
src/main/java/com/ecommerce/
  EcommerceApplication.java
  shared/          value types every module may use (open module)
  platform/        roles, HTTP conventions, migrations; later the outbox, inbox, idempotency, tasks, audit
  catalog/ customer/ cart/ pricing/ ordering/ inventory/ payments/ fulfillment/ notifications/
src/main/resources/
  application.yml, application-local.yml
  db/migration/<module>/V<n>__<description>.sql
src/test/java/com/ecommerce/
  LocalDevApplication.java   local run on embedded PostgreSQL
  architecture/              module and coding rules
  platform/                  roles, HTTP conventions, migrations
  support/                   embedded PostgreSQL
docs/
```

### 1.2 Modules and their boundaries

- The main class lives in `com.ecommerce`; each direct sub-package is a Spring Modulith module ([ADR-004](decisions/ADR-004-modular-monolith.md)).
- A module's **base package is its API**: the types other modules may use (module query interfaces, commands, replies, events, annotations). **Sub-packages are internal** (`domain`, `infrastructure`, `web`, …). Spring Modulith rejects any access to another module's internal types.
- Allowed dependencies are declared in each module's `package-info.java` and verified at build time:

| Module | May depend on |
|---|---|
| `shared` | Nothing (open module: all its types are API) |
| `platform` | `shared` |
| `catalog`, `customer`, `inventory`, `payments`, `fulfillment` | `platform`, `shared` |
| `pricing` | `catalog`, `platform`, `shared` |
| `cart` | `catalog`, `pricing`, `platform`, `shared` |
| `ordering` | `pricing`, `customer`, `inventory`, `payments`, `fulfillment`, `platform`, `shared` |
| `notifications` | `ordering`, `customer`, `platform`, `shared` |

Dependencies point from the saga to its participants: Ordering knows the commands and replies that Inventory, Payments and Fulfillment publish in their APIs; the participants never know Ordering. Kafka integration events cross modules as JSON contracts, not Java types, so the availability hints from Inventory to Catalog create no compile-time dependency.

### 1.3 Roles

- `ecom.roles` (environment `ECOM_ROLES`): a comma-separated subset of `api` and `worker`. Default: both. An empty value fails startup.
- `@ApiController` = `@RestController` + `@ConditionalOnRole(API)`. Every public controller uses it; an architecture test rejects a bare `@RestController` in a module.
- `@WorkerComponent` = `@Component` + `@ConditionalOnRole(WORKER)`, for background work from phase 2.
- Every instance runs the management server on port 8081 (health, info). Port 8080 serves the public API only where the `api` role is active; a worker-only instance answers 404 there.
- `/actuator/info` reports the active roles.

### 1.4 Configuration

| Profile | Use |
|---|---|
| (default) | Containers and the cloud: everything from environment variables |
| `local` | A developer machine: plain logs, debug logging for `com.ecommerce` |
| `test` | Tests |

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/ecommerce` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `ecommerce`, empty | Credentials |
| `DB_POOL_SIZE` | 20 | Hikari pool size |
| `ECOM_ROLES` | `api,worker` | Roles of this instance |
| `PORT`, `MANAGEMENT_PORT` | 8080, 8081 | HTTP ports |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset; `ecs` in the image | JSON logs |

Typed properties (`@ConfigurationProperties`, validated) fail startup on invalid values rather than at first use.

### 1.5 HTTP conventions

- JSON in `snake_case`; null fields omitted; timestamps in ISO-8601 UTC.
- **Request ids.** An incoming `X-Request-Id` is kept if it matches `[A-Za-z0-9._-]{1,64}`; otherwise a `req_<32 hex>` id is generated. It is echoed on every response and put in the logging context (MDC) as `request_id`.
- **Errors** are RFC 9457 problem details (`application/problem+json`) with two extensions: `code`, stable and documented, and `request_id`. Validation errors add `errors: [{field, message}]`. A `500` never carries an exception message.

| Code | Status | When |
|---|---|---|
| `invalid_request` | 400 | Missing or mistyped parameter or header |
| `malformed_request` | 400 | Unreadable body |
| `validation_failed` | 400 | Constraint violations; `errors` lists the fields |
| `not_found` | 404 | No such resource or route |
| `method_not_allowed` | 405 | Wrong method |
| `not_acceptable` | 406 | No acceptable representation |
| `unsupported_media_type` | 415 | Wrong content type |
| `internal_error` | 500 | Anything unexpected, logged with its request id |

Modules raise `ApiException` (status, code, detail) for their own errors; the codes are listed in `api.md` as they appear.

- Virtual threads; graceful shutdown (Spring Boot's 30-second default).

### 1.6 Database and migrations

- One database; one schema per module; one application role in V1 ([ADR-005](decisions/ADR-005-postgresql.md)).
- **Each module has its own Flyway history.** Its migrations live under `db/migration/<module>/`, and its history table is `<module>.flyway_schema_history`, so the history moves with the module at extraction.
- A `FlywayMigrationStrategy` runs Spring Boot's Flyway configuration once per module, `platform` first, then the business modules. Spring Boot still orders database initialization before anything uses the database.
- Each module's first migration, `V1__schema_owner.sql`, records its owner on the schema (`COMMENT ON SCHEMA`). Every module therefore owns a schema and a history from phase 1, before it has tables.
- Rules: forward-only; expand/contract when a running version depends on the old shape; versions per module start at V1.

### 1.7 Health

| Probe | Includes | Paths |
|---|---|---|
| Liveness | `livenessState` only: never a dependency | `:8081/actuator/health/liveness`, `:8080/livez` |
| Readiness | `readinessState`, `db` | `:8081/actuator/health/readiness`, `:8080/readyz` |

### 1.8 Logging

- In containers, structured JSON (Elastic Common Schema), enabled by `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in the image. MDC fields such as `request_id` become JSON fields.
- Locally, plain text with the request id after the level.
- No `System.out`, no `java.util.logging` (architecture test). Personal data is masked from phase 13.

### 1.9 Local runs

- `./gradlew bootTestRun`: embedded PostgreSQL (data kept in `.embedded-pg/`, port 55433), `local` profile, both roles. No Docker needed.
- `docker compose up --build`: PostgreSQL 17 (host port 5433) and the app (8080, 8081).
- **Change from the plan:** each later phase adds the containers it needs (Keycloak and MinIO in phase 3, the gateway in phase 7, Kafka and Mailpit in phase 9, the scheduler in phase 10, Grafana LGTM in phase 12), so `docker compose up` starts only what the code uses.

### 1.10 Container image

Two stages: JDK 25 to build, JRE 25 to run. The jar is extracted in layers (dependencies, loader, application) so code changes rebuild only the top layer. Non-root user; `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`; JSON logs.

### 1.11 Continuous integration (GitHub Actions)

- **build:** Temurin JDK 25, Gradle, `./gradlew build`: compile with `-Werror`, then all tests. Test reports are uploaded on failure.
- **container:** after build. Builds the image, starts `docker compose`, waits for readiness on port 8081, checks the roles reported by the info endpoint, and checks that an unknown route answers with a `not_found` problem.

### 1.12 Tests

| Test | Proves |
|---|---|
| `ModularityTests` | Spring Modulith detects all eleven modules and accepts their allowed dependencies |
| `ModuleBoundaryEnforcementTests` | A fixture (root marked `@Modulithic`) with a forbidden dependency is reported, so the check really can fail the build |
| `ArchitectureTests` | Coding rules: no field injection, no standard streams, no `java.util.logging`, no generic exceptions, public controllers use `@ApiController`. Module layering is Spring Modulith's job |
| `ApiRoleTests`, `WorkerRoleTests`, `BothRolesTests` | The app starts on embedded PostgreSQL in each role. Info reports the roles; liveness and readiness are up on both ports; the public API is served only in the `api` role |
| `ModuleMigrationsTests` | Every module has its schema, its own history with V1 applied and its owner comment; every migration folder belongs to a registered module |
| `RolePropertiesTests` | `ecom.roles` binds case-insensitively; an empty or unknown role fails startup |
| `ProblemDetailsTests` | The error model for 400 (malformed and validation), 404, 405, 409 (module error), 415 and 500, and request id propagation |

### 1.13 Exit criteria

| Criterion | Shown by |
|---|---|
| `./gradlew build` green in CI | The build job |
| The app starts in both roles | The role tests; the compose smoke test in CI |
| A forbidden module dependency fails the build | `ModuleBoundaryEnforcementTests` and `ModularityTests`. Checked by hand once: a catalog class using an inventory-internal type failed the build with "Module 'catalog' depends on module 'inventory' … Allowed targets: platform, shared" |

## 2. Platform: messages, tasks, idempotency, audit (phase 2)

### 2.1 Scope

Phase 2 builds the machinery every later phase relies on ([ADR-008](decisions/ADR-008-transactional-outbox.md), [ADR-010](decisions/ADR-010-idempotency.md), [ADR-003](decisions/ADR-003-job-scheduler-integration.md)):

- **Messages:** the outbox, the in-process dispatcher, the Kafka relay, deduplication of processed messages, and LISTEN/NOTIFY wake-ups.
- **Tasks:** the `TaskScheduler` port with its in-process adapter, including recurring tasks.
- **API idempotency keys** and the **audit log**.
- **Basics:** time-ordered ids (UUIDv7), a microsecond clock, `@WorkerComponent`.

Not yet: real message types (phases 5–9), the Kafka container (phase 9, with the first integration event; phase 2 tests the relay on embedded Kafka, a correction to §1.9), the Job Scheduler adapter (phase 10), metrics (phase 12).

### 2.2 Publishing

```java
@MessageType(name = "inventory.stock-reserved", version = 1)            // topic = "…" for integration events
public record StockReserved(UUID orderId, UUID reservationId, Instant expiresAt) { }

messages.publish(new StockReserved(...),
        new Origin("reservation", reservationId, reservationVersion),    // aggregate and its version
        Correlation.causedBy(incoming));                                // or Correlation.start(orderId)
```

- `Messages.publish` runs only inside the caller's transaction (`MANDATORY`), so a message exists if and only if its state change committed.
- **Routing by type and version.** The platform writes one outbox row per internal handler subscribed to the type and version, plus one row for the type's Kafka topic, if it has one. A type with no route is a programming error and fails the publish.
- The envelope ([event model §2](event-model.md#2-envelope)) is serialized once and stored as text; `source` is derived from the payload's module.
- The same transaction issues `NOTIFY ecom_outbox`, which PostgreSQL delivers at commit.

`platform.outbox`:

| Column | Notes |
|---|---|
| `id` | Identity; row identity only, not delivery order |
| `message_id` | UUIDv7; unique per `destination` |
| `destination` | `handler:<consumer>` or `kafka:<topic>` |
| `aggregate_type`, `aggregate_id`, `sequence` | Delivery order within an aggregate |
| `type`, `envelope` | Message type; the serialized envelope |
| `attempts`, `next_attempt_at`, `last_error` | Retries |
| `parked_at`, `delivered_at` | Terminal states; `NULL` in both means pending |

### 2.3 Ordering per aggregate, without lanes (amends ADR-008)

A pending row is **eligible** only if no earlier pending row exists for the same destination and aggregate, where "earlier" means a lower `(sequence, id)`. Workers claim eligible rows with `FOR UPDATE SKIP LOCKED`.

- **Why `sequence`, not `id`:** identity values are allocated at insert but become visible at commit, which can happen in a different order. An aggregate's version is assigned under its row lock, so it follows commit order.
- **Why no lanes:** the eligibility rule already gives per-aggregate order. Different aggregates are delivered in parallel by any number of workers, with no leases to manage.
- **A failing row blocks only its own aggregate.** A parked row (see §2.4) keeps blocking it until an operator re-drives or discards it.

### 2.4 Dispatching to handlers

```java
@HandlesMessage(consumer = "inventory.reserve-stock")
void on(IncomingMessage<ReserveStock> message) { ... }
```

Each delivery is one transaction:

1. Claim one eligible row.
2. Skip the handler if `processed_messages` already has (consumer, message id).
3. Run the handler.
4. Insert the `processed_messages` row.
5. Mark the outbox row delivered.

If the handler throws, everything rolls back. A second transaction then increments `attempts` and sets `next_attempt_at` with jittered exponential backoff (1 s doubling to 5 min). After 10 failures, or at once for an unreadable payload, the row is parked.

Handler rules:

- Handlers are idempotent.
- They may publish messages and schedule tasks in the same transaction.
- They **never call external systems.** They record intent and schedule a task, so transactions stay short and retries stay safe.
- `correlation_id` and `message_id` are in the MDC while a handler runs.

Within one process the transaction already makes effects exactly-once. The `processed_messages` guard is there so that handlers behave the same when V2 moves these channels to Kafka.

### 2.5 Relaying to Kafka

Each relay transaction:

1. Claims up to 100 eligible `kafka:` rows. The eligibility rule allows at most one row per aggregate in a batch.
2. Sends them all. The key is the aggregate id, the value is the envelope, and the headers are `type`, `version` and `traceparent`.
3. Waits up to 10 s for every acknowledgement.
4. Marks the rows delivered and commits.

Any failure rolls back the batch and backs it off. A crash after the acknowledgements but before commit sends the batch again: delivery is at-least-once, and consumers deduplicate on `message_id`.

**Relay failures never park a row.** They come from the broker, not the message, so rows wait out an outage of any length with backoff capped at 5 minutes.

The producer uses `acks=all` with idempotence enabled, and `max.block.ms` is 5 s so an unreachable broker cannot stall the relay for a minute. Kafka is not a readiness dependency: while it is down, the outbox buffers (NFR-8).

### 2.6 Waking up

Each worker instance holds one connection that listens on `ecom_outbox` and `ecom_tasks`, and wakes the matching loops on each notification. Polling every 500 ms is the backstop. A broken listener reconnects with backoff, and polling covers the gap.

**An instance claims only work it can do:** the dispatcher claims rows for its own handlers' destinations, and the task runner claims only types it has handlers for. During a rolling deployment, an old instance therefore never takes, and parks, messages or tasks for a handler that only the new version has.

### 2.7 Tasks

```java
taskScheduler.schedule(TaskRequest.of("fulfillment.book-shipment", payload)
        .dedupeKey("shipment:" + shipmentId));                // MANDATORY transaction

@HandlesTask(type = "fulfillment.book-shipment")
void book(TaskExecution<BookShipment> task) { ... }          // runs outside any transaction

@HandlesTask(type = "platform.cleanup", every = "1h")          // recurring
void cleanup(TaskExecution<Void> task) { ... }
```

In-process adapter, table `platform.scheduled_tasks`:

| Step | Behavior |
|---|---|
| Schedule | Insert in the caller's transaction and notify `ecom_tasks`. A `dedupe_key` is unique among pending and running tasks; a duplicate is ignored |
| Claim | `UPDATE … SET status = 'RUNNING', attempts = attempts + 1, lease_until = now() + lease … RETURNING`, over due pending tasks and running tasks whose lease expired (a crashed worker) |
| Run | The handler runs outside any transaction, with the task id as its idempotency key |
| Complete | Only if still `RUNNING` with the same attempt number (fencing); a stale worker's completion is ignored |
| Fail | Retry with exponential backoff (10 s doubling to 1 h); `DEAD` after the task's maximum attempts (default 10) |
| Recurring | One row per type, registered at startup. The next run is the first slot of the schedule after now, so runs missed while the system was down collapse into one. A failed run waits for the next interval. The attempt counter keeps growing across runs, so fencing also works between runs |

Phase 10 replaces the adapter (submission through the outbox, the gRPC worker) and keeps this API.

### 2.8 API idempotency keys

```java
return idempotency.execute(
        IdempotentRequest.of(scope, idempotencyKeyHeader, "POST /v1/orders", requestBody),
        () -> ResponseEntity.accepted().body(ordering.place(...)));
```

The key and the action share **one transaction**, so the action's effects and the stored response commit together:

1. Delete the row if it has expired (after 24 hours).
2. Insert the `(scope, key)` row with a 200 ms lock timeout.
3. Then one of the following:
   - **Inserted:** run the action, store its status, body and `Location`, and commit.
   - **The key exists with a different fingerprint:** `422 idempotency_key_reused`.
   - **The key exists with the same fingerprint:** replay the stored response with `Idempotent-Replayed: true`.
   - **The lock timeout fired** (another request with this key is still running): `409 idempotency_request_in_progress` with `Retry-After: 1`. Spring does not translate PostgreSQL's `lock_not_available` (`55P03`), so the code checks the SQL state.

- **Fingerprint:** SHA-256 of the operation and the canonical JSON of the parsed body, so reformatting the JSON does not change it.
- **Releasing the key:** if the action throws, the rollback removes the key, so the client can retry.
- **Bad keys:** a missing key is `400 idempotency_key_required`; one that is not 1–255 printable ASCII characters is `400 invalid_idempotency_key`.

### 2.9 Audit log

`AuditLog.record(AuditEntry)` runs in the caller's transaction.

- **Columns:** actor type and id, action, target type and id, reason, details (JSON), plus `request_id` and `correlation_id` taken from the MDC.
- **Append-only:** a trigger rejects `UPDATE` and `DELETE`. Retention will drop old monthly partitions, which row triggers do not block.

### 2.10 Identifiers and time

- `Ids.newId()`: UUIDv7 from `SecureRandom`, with 74 random bits, so it is unguessable as well as time-ordered.
- **Clock:** a `Clock` bean in UTC that ticks in microseconds, the precision of `timestamptz`.
- **Due times and leases** are compared with PostgreSQL's `now()`, so instances never disagree about time.

### 2.11 Configuration

| Property | Default |
|---|---|
| `ecom.workers.autostart` | `true`. Tests set `false` and drive the loops directly |
| `ecom.messaging.dispatcher-concurrency` | 4 |
| `ecom.messaging.poll-interval` | 500 ms |
| `ecom.messaging.max-attempts` | 10 |
| `ecom.messaging.initial-backoff`, `max-backoff` | 1 s, 5 min |
| `ecom.messaging.relay-batch-size`, `relay-send-timeout` | 100, 10 s |
| `ecom.tasks.concurrency`, `poll-interval`, `default-lease` | 4, 500 ms, 5 min |
| `ecom.tasks.initial-backoff`, `max-backoff` | 10 s, 1 h |
| `ecom.idempotency.key-ttl`, `lock-timeout` | 24 h, 200 ms |
| `ecom.cleanup.delivered-messages`, `processed-messages`, `finished-tasks` | 7 days, 30 days, 30 days |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

### 2.12 Cleanup

The recurring task `platform.cleanup` runs every hour. In batches of 1,000 rows, it deletes:

- delivered outbox rows older than 7 days;
- processed-message records older than 30 days;
- expired idempotency keys;
- succeeded or dead tasks older than 30 days.

### 2.13 Tests

Each crash between steps is simulated by failing at that step (a handler that throws after its write, a trigger that rejects marking a row delivered, a lease left to expire), then running the next attempt.

| Test | Proves |
|---|---|
| `MessagesTests` | Publishing needs a transaction. Rollback leaves no message. Commit fans out to every route. The envelope has the event-model fields. An unroutable type fails |
| `DispatcherTests` | A failure after the handler's write rolls it back, and the retry applies it exactly once. A delivered message is not delivered again. An existing processed-message record skips the handler. Messages committed out of order arrive in sequence order. A failing message holds back only its own aggregate. Parking and re-drive work. An unreadable message parks at once. Four concurrent workers deliver 400 deliveries exactly once, in order per aggregate |
| `BackgroundDeliveryTests`, `BackgroundTaskRunTests` | With polling set to an hour, the notification alone delivers a message, or runs a task, within seconds |
| `KafkaRelayTests` | Key, value and headers on embedded Kafka. A crash between the send and the commit sends again, with the same `message_id`. At most one row per aggregate in a batch |
| `KafkaUnavailableTests` | With an unreachable broker, rows stay pending, back off, and are never parked |
| `TaskSchedulerTests` | Scheduling needs a transaction. Rollback leaves no task. Retries, then `DEAD`. An unreadable payload is `DEAD` at once. Deduplication. A future task waits. A dead worker's lease is taken over, and fencing rejects its late result. Four runners execute each task once. Recurring tasks: fixed rate, missed runs collapse, a failed run waits, registration is idempotent |
| `PlatformCleanupTests` | Only finished records past their retention are deleted, in batches |
| `IdempotentRequestsTests` | Replay with the same response, `422` for a different body, keys scoped per caller, `400` for missing or malformed keys, release on failure, `409` with `Retry-After`, expiry |
| `AuditLogTests` | Records in the caller's transaction with the request and correlation ids; `UPDATE` and `DELETE` are rejected |
| `IdsTests` | Version 7, variant, time order, uniqueness |

### 2.14 Exit criteria

| Criterion | Shown by |
|---|---|
| No lost effects across crashes between steps | `MessagesTests`, `DispatcherTests`, `KafkaRelayTests`, `TaskSchedulerTests` |
| No duplicated effects | `DispatcherTests` (processed messages, concurrency), `IdempotentRequestsTests`, task fencing |
