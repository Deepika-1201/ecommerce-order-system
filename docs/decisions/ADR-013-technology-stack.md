# ADR-013: Technology stack

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [ADR-005](ADR-005-postgresql.md), [ADR-006](ADR-006-kafka.md), [ADR-014](ADR-014-deployment.md)

## Context

- Stakeholder defaults accepted on 2026-10-02: Java 25 with Spring Boot 4, as in Payment-Orchestrator; Kafka; no storefront UI.
- The stack must support the outbox, a Kafka client with dead-letter handling, a gRPC client for the scheduler's worker protocol, OIDC, OpenTelemetry, and tests that run without Docker.
- Lessons from Payment-Orchestrator carry over directly: Jackson 3 packages; verifying Boot property names against the jar's metadata; microsecond clocks.

## Problem

Which language, framework and libraries does the system use?

## Options considered and decision

| Area | Options | Decision | Reason | Future implications |
|---|---|---|---|---|
| Language and framework | Java with Spring Boot; Kotlin with Spring; Go; TypeScript with NestJS | **Java 25, Spring Boot 4.1**, Spring MVC on virtual threads | Same stack as the gateway; mature Kafka, gRPC, OIDC and observability support; virtual threads keep blocking code simple at high concurrency | Go and TypeScript stay in the portfolio through the scheduler and Loom-Hub |
| Module boundaries | Packages with ArchUnit; Spring Modulith; Gradle subprojects | **Spring Modulith** verification plus ArchUnit rules, in one Gradle project | Checks allowed dependencies between modules and documents them; subprojects add build friction before they add safety | Modules can become subprojects or services in V3 |
| Data access | JPA; Spring Data JDBC; jOOQ; plain JDBC | **Spring Data JDBC** plus **JdbcClient** | [ADR-005](ADR-005-postgresql.md) | — |
| Migrations | Flyway; Liquibase | **Flyway**, organized per module | As in the gateway | A module's migration history moves with it |
| Kafka client | Spring for Apache Kafka; plain clients | **Spring for Apache Kafka** | Error handlers, dead-letter publishing, observation, embedded broker for tests | — |
| Scheduler worker | grpc-java; Armeria | **grpc-java**, stubs generated from the scheduler's proto | The proto is the contract; standard tooling | The proto file is vendored, pinned to a scheduler commit |
| HTTP clients | RestClient; WebClient; Feign | **RestClient** with Resilience4j circuit breakers | Blocking calls on virtual threads, as in the gateway | — |
| Identity | Keycloak; Cognito; Auth0; built in | **Keycloak** (OIDC) locally, Spring Security resource server; the AWS choice is made in phase 17 | Standard OIDC; nothing custom to secure | Swapping the provider only changes configuration |
| Observability | OpenTelemetry with Prometheus and Grafana; a vendor agent | **OpenTelemetry** (Spring Boot starter), Micrometer metrics in Prometheus format, Grafana LGTM locally | Same as the sibling projects; vendor-neutral | — |
| Testing | Testcontainers; embedded infrastructure | **JUnit Jupiter**, **embedded PostgreSQL** (zonky) and **embedded Kafka**, so tests need no Docker; Testcontainers only for tests against the real gateway and scheduler; **k6** for load | Same no-Docker approach as the gateway; Docker only where a real sibling system is needed | — |
| Build and CI | Gradle with the Kotlin DSL; Maven | **Gradle (Kotlin DSL)**, **GitHub Actions** | As in the gateway | — |
| Local environment | docker compose; Tilt; kind only | **docker compose**: PostgreSQL 17, Kafka (KRaft), Keycloak, Mailpit, MinIO, Grafana LGTM; an `ecosystem` profile adds the gateway and scheduler | One command, as the brief asks | Kubernetes manifests are tested on kind in phase 17 |

Exact versions are pinned at scaffolding from Spring Initializr's metadata and the libraries' release notes, not guessed.

## Trade-offs

- Java 25 and Spring Boot 4.1 are new. Their pitfalls are already documented from Payment-Orchestrator, which reduces the risk.
- Embedded Kafka and PostgreSQL in tests are close to, but not exactly, production. Ecosystem tests and the deployment phase run against the real ones.

## Consequences

- Phase 1 scaffolds this stack, with a module-boundary test that fails on a forbidden dependency from day one.
- The scheduler's proto is vendored with its source commit recorded. A protocol change shows up as a failing integration test, not a runtime surprise.
