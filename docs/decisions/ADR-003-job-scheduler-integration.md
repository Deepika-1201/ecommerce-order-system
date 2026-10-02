# ADR-003: Background tasks on the ecosystem's Job Scheduler

- **Status:** Accepted (2026-10-02), amended the same day by the HLD (see the end)
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
| **B. Scheduler REST API, plus a Java gRPC worker in this system** | Works with the scheduler as it is; all work stays in this repository | Re-implements the worker protocol (sessions, heartbeats, self-fencing) in Java, for one system |
| C. Scheduler REST API, plus an HTTP executor in the scheduler | One executor serves every system in any language; already on the scheduler's roadmap; handlers here are plain idempotent HTTP endpoints | Needs work in the scheduler repository, outside this project's scope; one more network hop; the executor needs request signing and an SSRF guard |
| D. Scheduler reads job requests from the broker | Matches the diagram's arrow | Changes the scheduler's intake; submission errors become asynchronous; no advantage over C until several systems need it |

## Decision

**Option B** (proposed). It needs no change to the scheduler, so all the work stays in this repository. Option C remains the better long-term path if the scheduler ships HTTP executors; it would replace the worker behind the same port.

- **Submission through the outbox.** A task is written to this system's outbox in the same transaction as the state change that needs it. The relay submits it with `Idempotency-Key` = the outbox record id and `dedupe_key` = a business key such as `shipment:{id}:booking`. Relay retries cannot create duplicates, and a scheduler outage only delays submission.
- **Worker.** A worker inside this system implements the scheduler's gRPC protocol (`proto/jobscheduler/worker/v1/worker.proto` in its repository). It registers in this system's pool with the job types it handles, long-polls for assignments, heartbeats at the interval returned at registration (5 s by default), and reports each outcome with the attempt number as fencing token. If it cannot renew its session within the lease, it fences itself: it stops its handlers, discards their results and registers again.
- **Handlers** run in-process, are idempotent on the job id, and re-read current state: a deadline task for an order that is already paid does nothing. Tasks are not cancelled on the happy path; cancelling needs the job id and is only an optimization.
- **Correctness does not depend on punctuality.** Reservations carry `expires_at`, and reserving treats expired reservations as released. Payment expiry is decided by the gateway (ADR-002). A late or missing task delays cleanup and notifications, never an invariant.
- **Priorities:** deadlines `HIGH` (never shed), retried external calls and notifications `NORMAL`, cleanup `LOW`. The relay retries `429` and `503` answers.
- **Isolation.** This system gets its own pool. Scheduler workers share one token until its phase 12 adds per-pool credentials, so until then isolation rests on pool configuration.
- **Recurring jobs** are scheduler schedules in the `Asia/Kolkata` time zone.
- **Port and adapters.** A `TaskScheduler` port, with an adapter for the scheduler and an in-process implementation for standalone runs and tests.
- **Tracing.** The scheduler stores the submitter's `traceparent` and passes it in each assignment; the worker links its execution span to it.

## Trade-offs

- Background work depends on another system. Mitigated: invariants don't depend on it, and the outbox buffers submissions.
- The worker protocol (sessions, long polls, heartbeats, self-fencing) is re-implemented in Java. It is tested against the real scheduler, because a fake would only confirm this project's own reading of the protocol.
- Until per-pool credentials exist, a misconfigured worker from another system could take this system's jobs. Acceptable for a reference implementation, and documented as a known limitation.

## Consequences

- No change to the scheduler is needed. If it adds HTTP executors, option C can replace the worker behind the `TaskScheduler` port.
- Local runs use the in-process adapter, or the real scheduler through the optional compose profile.
- Integration tests for the worker need the scheduler's container image, built from its repository.

## Amendment (2026-10-02, from the HLD)

Deadlines and cleanup run as **recurring sweeps**, not as one delayed job per order. At NFR-1's peak of 200 orders/s, a deadline job per order would add about 200 submissions/s against the scheduler's default tenant limit of 500/s, for timers that are only backstops: the gateway's webhooks drive the normal path. A sweep every minute finds overdue orders through an index on `deadline_at`, so a backstop fires at most a minute late, which does not matter for deadlines measured in tens of minutes. One job per item is kept where per-item retries and dead-lettering matter: shipment bookings ([architecture §16](../architecture.md#16-scalability)).
