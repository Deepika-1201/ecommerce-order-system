# ADR-003: Background tasks on the ecosystem's Job Scheduler

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [ADR-001](ADR-001-ecosystem-boundaries.md), [ADR-002](ADR-002-payment-gateway-integration.md), [requirements §5.10](../requirements.md#510-events-and-background-work)

## Context

This system needs three kinds of background work:

| Kind | Examples |
|---|---|
| Deadlines | Resolve an order whose payment has no outcome by its deadline; release expired reservations |
| Retried calls to other parties | Book a shipment with a carrier; send a notification |
| Recurring jobs | Reconcile orders against gateway payments; expire carts; prune outbox and inbox tables |

The ecosystem's Job Scheduler (its `api/openapi.yaml`, HLD and LLD) offers `POST /v1/jobs` with `run_at` or `delay`, priorities, retry policies (exponential backoff, maximum attempts, deadlines), labels, an optional `Idempotency-Key` (kept 24 hours) and a `dedupe_key` unique among active jobs. Jobs can be cancelled by id only. Schedules support cron with time zones, misfire and overlap policies, and a 60-second minimum interval. Execution is at-least-once, with the job id as the handler's idempotency key, plus dead-lettering and re-drive. Dispatch p99 is about 1 s. Under backlog, `LOW` and `NORMAL` submissions are shed.

Its limits for a second system:

- Jobs run only on gRPC workers, and the only SDK is Go. "HTTP and container executors" are on its roadmap ("Later" in its implementation plan).
- All workers share one token. Per-pool worker credentials belong to its phase 12 (security hardening, in progress).
- It has no broker intake, so the diagram's broker → scheduler arrow has no implementation yet.

## Problem

How does this system schedule and run background work on the scheduler, without its invariants depending on the scheduler being available or on time?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Timers in this system's database (`FOR UPDATE SKIP LOCKED` pollers) | Simplest; transactional with state changes | Ignores the ecosystem; re-implements retries, dead-lettering and cron |
| B. Scheduler REST API, plus a Java gRPC worker in this system | Works with the scheduler as it is | Re-implements the worker protocol (sessions, heartbeats, self-fencing) in Java, for one system |
| **C. Scheduler REST API, plus an HTTP executor in the scheduler** | One executor serves every system in any language; already on the scheduler's roadmap; handlers here are plain idempotent HTTP endpoints | Work in the scheduler repository; one more network hop; the executor needs request signing and an SSRF guard |
| D. Scheduler reads job requests from the broker | Matches the diagram's arrow | Changes the scheduler's intake; submission errors become asynchronous; no advantage over C until several systems need it |

## Decision

**Option C** (proposed), with B as the fallback if the scheduler work is deferred.

- **Submission through the outbox.** A task is written to this system's outbox in the same transaction as the state change that needs it. The relay submits it with `Idempotency-Key` = the outbox record id and `dedupe_key` = a business key such as `order:{id}:payment-deadline`. Relay retries cannot create duplicates, and a scheduler outage only delays submission.
- **Handlers** are idempotent on the job id and re-read current state: a deadline task for an order that is already paid does nothing. Tasks are not cancelled on the happy path; cancelling needs the job id and is only an optimization.
- **Correctness does not depend on punctuality.** Reservations carry `expires_at`, and reserving treats expired reservations as released. Payment expiry is decided by the gateway (ADR-002). A late or missing task delays cleanup and notifications, never an invariant.
- **Priorities:** deadlines `HIGH` (never shed), retried external calls and notifications `NORMAL`, cleanup `LOW`. The relay retries `429` and `503` answers.
- **Isolation.** This system gets its own pool. A shared deployment needs per-pool worker credentials (scheduler phase 12), so that no system's executor runs another system's jobs.
- **Recurring jobs** are scheduler schedules in the `Asia/Kolkata` time zone.
- **Port and adapters.** A `TaskScheduler` port, with an adapter for the scheduler and an in-process implementation for standalone runs and tests.
- **Tracing.** The scheduler stores the submitter's `traceparent` and links the execution span to it.

## Trade-offs

- Background work depends on another system. Mitigated: invariants don't depend on it, and the outbox buffers submissions.
- The extra hop (scheduler → HTTP executor → this system) adds latency. That is irrelevant for deadlines measured in minutes, and acceptable for retries.
- The HTTP executor must sign its requests so that targets can authenticate the scheduler, refuse targets outside an allowlist (SSRF), and map responses onto the retry policy: `2xx` succeeds, `4xx` fails permanently, `5xx` and timeouts retry.
- Option C needs work in the scheduler repository first. Until then, this system runs on the in-process adapter.

## Consequences

- The scheduler gets an ADR for the HTTP executor in its own repository; ride-hailing can use the executor too.
- This system exposes internal task endpoints that are not routed through the public edge and are authenticated by the executor's signature.
- If option C slips, option B (a Java worker) replaces it behind the same `TaskScheduler` port.
