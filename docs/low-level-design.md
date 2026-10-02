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
- **Change from the plan:** each later phase adds the containers it needs (Kafka in phase 2, Keycloak and MinIO in phase 3, the gateway in phase 7, Mailpit in phase 9, the scheduler in phase 10, Grafana LGTM in phase 12), so `docker compose up` starts only what the code uses.

### 1.10 Container image

Two stages: JDK 25 to build, JRE 25 to run. The jar is extracted in layers (dependencies, loader, application) so code changes rebuild only the top layer. Non-root user; `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`; JSON logs.

### 1.11 Continuous integration (GitHub Actions)

- **build:** Temurin JDK 25, Gradle, `./gradlew build`: compile with `-Werror`, then all tests. Test reports are uploaded on failure.
- **container:** after build. Builds the image, starts `docker compose`, waits for readiness on port 8081, and checks the info endpoint's roles.

### 1.12 Tests

| Test | Proves |
|---|---|
| `ModularityTests` | Spring Modulith accepts the module structure and allowed dependencies |
| `ModuleBoundaryEnforcementTests` | A fixture with a forbidden dependency is reported, so the check really can fail the build |
| `ArchitectureTests` | Coding rules: no field injection, no standard streams, no `java.util.logging`, public controllers use `@ApiController`, `shared` and `platform` depend on no business module |
| `ApplicationRolesTests` | The app starts on embedded PostgreSQL in the `api`, `worker` and combined roles. Info reports the roles; readiness is up; every module has its schema and history; the public API is served only in the `api` role |
| `ProblemDetailsTests` | The error model for 400, 404, 405, 415 and 500, and request id propagation |

### 1.13 Exit criteria

| Criterion | Shown by |
|---|---|
| `./gradlew build` green in CI | The build job |
| The app starts in both roles | `ApplicationRolesTests`; the compose smoke test in CI |
| A forbidden module dependency fails the build | `ModuleBoundaryEnforcementTests` and `ModularityTests` |
