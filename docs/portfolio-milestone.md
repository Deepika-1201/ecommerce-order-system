# Portfolio milestone

Accepted scope: 2026-10-14. This is a separate delivery target, not a replacement for
the [full implementation plan](implementation-plan.md). That plan and its phase
statuses remain unchanged. Completing this milestone does not mean completing V1
or claiming production readiness.

**Completed 2026-10-15:** all six portfolio items passed their local and CI gates.
Start with [the reviewer guide](portfolio-guide.md) and its recorded rehearsal.
The larger roadmap's deferred phases remain future work.

## Goal

Demonstrate an event-driven modular monolith with a correct order saga, stock
reservations, real payment-gateway integration, recoverable asynchronous work,
and reproducible evidence. Phases 1-8 of the full plan are the starting point.
The last recorded baseline is 889 tests; new work must preserve those guarantees.

## Delivery checklist

Hours are rough incremental engineering estimates, including design, code, tests,
documentation, and CI. They are not elapsed AI-session time or a deadline.

| ID | Work | Full-plan reference | Estimated hours | Status |
|---|---|---|---|---|
| P1 | Kafka integration events and notifications | Phase 9 | 18-26 | Done: `872d073`, CI green |
| P2 | Focused failure and recovery demonstrations | Selected phase 11 work | 10-16 | Done: `272155e`, CI green |
| P3 | Essential metrics, dashboard, alerts, and runbook | Reduced phase 12 | 10-16 | Done: `55114a9`, CI green |
| P4 | Essential security hardening and CI checks | Reduced phase 13 | 8-12 | Done: `50f7629`, CI green |
| P5 | Measured load and stock-contention evidence | Reduced phase 16 | 12-20 | Done: `163b14a`, CI green |
| P6 | Reproducible demo and portfolio presentation | Selected phase 18 work | 6-10 | Done: `a972f7b`, CI green |
| Total | Portfolio milestone | | 64-100 | Complete |

### P1: Kafka events and notifications

- Run Kafka and Mailpit in the local compose stack.
- Publish the approved order and stock integration events through the existing
  transactional outbox; do not move internal saga messages to Kafka.
- Commit versioned event schemas and compatibility checks. Event data must not
  contain customer names, email addresses, delivery addresses, or other PII.
- Consume order events into a durable notification queue. Deduplicate by message
  ID in the same transaction as the queued effect, before acknowledging Kafka.
- Send email outside the consumer transaction with bounded timeouts and retries.
  SMTP delivery is at least once: a crash after the server accepts an email but
  before the database records success may produce a duplicate email.
- Send poison messages to a dead-letter topic. If dead-letter publication fails,
  do not advance the source offset. Keep an operator recovery procedure.
- Apply newer stock events to catalog availability hints; hints are never the
  authority for reservation or a guarantee that checkout will succeed.
- Exit: focused producer/consumer/schema tests, real-broker duplicate and poison
  tests, a Mailpit demonstration, and the existing build all pass.

### P2: Failure evidence

- Reuse existing crash, idempotency, inventory-concurrency, and saga tests.
- Map selected scenarios from [failure handling](failure-handling.md) to named,
  executable checks; clearly distinguish automated, demonstrated, and unverified.
- Cover broker outage and catch-up, duplicate delivery, poison messages, a failed
  notification send, and replay without repeated database effects.
- Exit: commands and results are recorded with no unsupported exactly-once claims.

### P3: Operational visibility

- Correlate API and asynchronous work by order/message ID without logging PII.
- Expose order outcomes, outbox backlog and age, consumer recovery failures, and
  notification backlog/failures with bounded-cardinality metric labels.
- Provide one useful dashboard, a small set of tested alert rules, and a runbook
  for backlog, dead-letter, and notification-delivery incidents.
- Exit: demonstrate finding and diagnosing a failed order flow from the evidence.

### P4: Security essentials

- Preserve existing authentication, authorization, ownership, and webhook tests.
- Add appropriate rate limits, review secret handling and PII logging, and scan
  shipped application/container dependencies in CI.
- Explicitly distinguish local demonstration credentials from deployment secrets.
- Exit: focused security regressions and delivery gates pass; remaining risks are
  documented rather than described as a completed OWASP certification.

### P5: Performance evidence

- Provide repeatable normal-load and stock-contention scenarios with assertions
  for response classification, stock invariants, and idempotent retries.
- Record hardware, runtime configuration, concurrency, duration, achieved rate,
  latency percentiles, errors, and observed bottlenecks.
- Exit: publish measured results and a repeatable command. A local benchmark is
  not evidence of the full plan's 100,000-user flash-sale target.

### P6: Portfolio packaging

- Verify a fresh-clone Docker workflow and a short end-to-end demonstration.
- Explain the architecture, compensation, ordering, deduplication, failure
  recovery, and important trade-offs using the existing diagrams and ADRs.
- Link the tests and measured results supporting the claims. Provide a walkthrough
  script suitable for recording; claim a recording only after one exists.
- Exit: a reviewer can run the demo and distinguish delivered work from future
  work without reading every design document.

## Deferred work

Real scheduler integration (phase 10), internal saga transport over Kafka (14),
Inventory service extraction (15), the waiting room and full flash-sale target
(16), AWS EKS/MSK/RDS infrastructure (17), and formal production-readiness approval
(18) remain in the original roadmap. No sibling repository changes are required.

## Working rules

- Write each slice's implementation design before its code, here or in a linked
  portfolio-specific design document. Record significant new decisions as ADRs.
- Keep changes scoped to this repository and preserve the original phase plan.
- Validate small slices first, then the existing build and relevant demo/CI gates.
- A row is done only when its exit criteria have evidence, its docs match the
  implementation, and the coherent change has been committed with green CI.
- Do not mark later rows done because an earlier row provides part of their work.

## P1 implementation design

### Transactional receive boundary

`platform.IntegrationInbox.receive(consumer, messageId, effect)` runs in its own
database transaction. It inserts the existing `(consumer, message_id)` unique
marker before executing the effect. A duplicate returns without executing it;
an exception rolls back both the marker and the effect. Returning successfully
means the transaction has committed, even if a caller has an outer transaction.
Kafka listeners acknowledge only after that return. Effects must only perform
transactional database work, such as queueing a notification or updating a hint.
Concurrent deliveries serialize on the unique marker, not an in-memory lock.

The first discriminating checks are duplicate/concurrent delivery and a failure
after a database write: the latter must leave neither the marker nor the write,
and a retry must succeed. Envelope validation happens before entering this
boundary; SMTP is deliberately outside it.

### Stock integration events

The stock repository publishes `stock.level_changed` after a successful change
to availability, in the stock transaction. The snapshot carries SKU, location,
and available units; its envelope sequence is the stock row version. Handover
changes on-hand and reserved equally, so does not publish an availability event.
The compacted key is the JSON array `[sku, location_code]` serialized as a string,
not SKU alone ([ADR-027](decisions/ADR-027-stock-event-keys.md)). Receipts,
adjustments, reservations, releases, expiry reclamation, and returns all pass
through this repository. Rolled-back mutations must leave no outbox events.

### Order integration events

Placement publishes `order.placed`. Saving a process publishes the final changed
customer status for confirmed, rejected, cancelled, shipped, delivered,
delivery-failed, and returned-to-origin orders. An unchanged status, deadline
retry, or duplicate reply produces no further milestone. If one input advances
through intermediate statuses, only the final committed status is published.
The key is the order UUID and the sequence is the saved process version.

Payloads contain customer and order IDs, amounts and milestone facts, never
contact details, address snapshots, checkout URLs, or free-form support notes.
Cancellation and return payloads include the recorded refund status: the existing
saga sets the order's final status when requesting a refund, not when the gateway
settles it. Notifications must not describe a requested refund as completed.
`delivered_at` is when Ordering records delivery, not a claimed carrier scan time.
An outbox failure must roll back placement or the status change with its commands.

### Event validation and compatibility

The runtime contract is [integration-event-v1.json](../src/main/resources/events/integration-event-v1.json).
`platform.IntegrationEvents` validates a maximum 256 KiB UTF-8 JSON record before
mapping it. The envelope, source, aggregate, topic, key and payload must agree;
missing fields, unsupported versions, invalid dates/UUIDs, negative quantities,
and out-of-range integers are permanent failures. Consumers use raw bytes so a
dead-letter publisher can preserve malformed keys and values without re-encoding.
Unknown optional fields remain allowed. The committed V1 payload examples and
additive-field tests are the backward-compatibility baseline, not a claim of a
general-purpose schema-evolution prover.

### Notification delivery

The consumer records its inbox marker, per-order sequence, unique `(order, kind)`
notification, and deduplicated send task together. Sending looks up the current
email through the Customer module, outside the Kafka consumer. The notification
stores only IDs and generated milestone text. The task retries SMTP with bounded
I/O timeouts, up to 20 attempts. Missing email becomes SKIPPED; exhausting delivery
attempts becomes FAILED, with no raw recipient-bearing SMTP error persisted.
[ADR-028](decisions/ADR-028-durable-email-notifications.md) records the crash window,
the at-least-once delivery limit, and the lack of strict email arrival ordering.

### Kafka listeners and dead letters

Integration listeners are worker-only and opt in with `ECOM_EVENTS_ENABLED=true`.
They inherit broker/security properties, disable auto-commit, and use synchronous
manual-immediate acknowledgements after inbox commit. Permanent schema failures
go directly to the group's DLT; other failures retry after 1, 2, and 4 seconds.
Recovery waits for broker acknowledgement, commits only on success, and retains
raw key/value bytes. DLT partition selection is independent of source partition
count. Published and failed recovery counters are registered per fixed group.

Kafka topics are declared on worker startup: 12 source partitions by default,
one partition per DLT, seven-day order retention, compacted stock, and 30-day DLT
retention. Replication and minimum ISR default to one for local use and are
configurable together; this is not a local replica-durability demonstration.
Background consumers obey `ecom.workers.autostart`, so tests can drive them
without enabling unrelated worker loops.

### Catalog availability hints

The stock consumer stores the latest snapshot per `(sku, location)` in Catalog's
own schema. Its version comparison and inbox marker share a transaction. Public
product variants expose `availability_hint`: UNKNOWN without a snapshot, IN_STOCK
if any known location has availability, otherwise SOLD_OUT. One batched query
serves all variants; no direct Inventory-table reads or per-variant queries are
introduced. This is a global inventory hint, not a destination-serviceability or
stock-reservation promise. Existing 30-second HTTP caching still applies.

## Running P1

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-events.sh
```

Mailpit captures email locally at `http://localhost:8025`; it does not deliver
messages to real recipients. The broker is reachable from the host at
`localhost:19092`. The overlay applies local memory limits, not production sizing.
A one-shot initializer assigns the dedicated Kafka volume to the broker's
non-root user; it does not change host permissions or other projects' data.
To also use the real gateway, append `:docker-compose.ecosystem.yml` to
`COMPOSE_FILE` before starting the stack. Existing payment and fulfillment demos
continue to use their real-gateway workflows.

The portfolio demo verifies receipt -> IN_STOCK hint, idempotent placement ->
SOLD_OUT hint, cancellation -> IN_STOCK hint, and exactly one notification intent
per kind captured by Mailpit in the no-crash demonstration. Fault behavior is
covered separately by named tests; this happy-path count is not an exactly-once
SMTP guarantee.

### Dead-letter recovery

1. Inspect the source topic, key, original partition/offset, and error headers in
  `<source-topic>.<consumer-group>.dlt`. DLT retention is 30 days.
2. Fix the schema/consumer or infrastructure failure first. Do not repeatedly
  replay malformed records or bypass validation to empty the DLT.
3. For a valid UTF-8 JSON record, republish its original key and envelope to the
  source topic, preserving `message_id` and `sequence`. The inbox and projection
  guards make already-applied effects harmless. Keep an operator record of the
  source offset, fix, and replay result.
4. Do not use text console tools for malformed UTF-8 or binary records: they are
  not lossless exports. Preserve those raw DLT records while investigating; a
  repaired event is a separately reviewed action, not a blind replay.
5. Verify source-group lag falls and the expected notification/hint exists. A DLT
  publication failure itself must be fixed at the broker; its source offset
  remains uncommitted and the listener retries recovery automatically.

## P2 implementation design

Reuse the existing failure tests rather than duplicating the saga suite. The
first additional check stops and restarts the same embedded Kafka broker: two
events for one aggregate must stay pending during the outage, then arrive with
their original message IDs in sequence. The source remains the real transactional
outbox and the relay is the production bean. Test-only shortened Kafka timeouts
bound the outage. A `finally` block restarts the broker even when an assertion
fails. This demonstrates single-broker recovery, not replica failover or quorum
durability.

A second check uses the production notification listener with a real record and
an acknowledgement that fails after the inbox transaction returns. Closing that
consumer without committing and recreating it with the same group must redeliver
the same offset, while preserving the original notification and send-task IDs.
This is an injected failure at the commit/acknowledgement boundary, not a claim
that the test terminates a JVM process.

A third check makes task insertion fail until the real listener exhausts its
transient retries and writes the valid record to the DLT. No inbox, progress, or
notification effect may survive the rollback. Removing the fault and replaying
the original key/value must create one durable effect, including when an operator
repeats the replay. Existing notification tests cover SMTP retry separately.
A named evidence matrix will distinguish existing regression coverage, new fault
demonstrations, and deferred scenarios. The full S1-S20 phase remains separate
from this focused milestone.

## Running P2

JDK 25 is required. Tests launch their own embedded PostgreSQL and Kafka; no
Docker stack or sibling repository is needed, and no running broker is stopped.

```sh
./gradlew portfolioFailureTest
```

The command always executes the selected tests rather than reporting an
up-to-date result. The HTML report is generated at
`build/reports/tests/portfolioFailureTest/index.html`, with JUnit XML under
`build/test-results/portfolioFailureTest`. The normal `./gradlew build` also
includes every selected test, so CI checks the same guarantees without a second
duplicate test job.

### Failure evidence matrix

The scenario IDs refer to the unchanged [full failure analysis](failure-handling.md).
Class links below identify the executable evidence; this is a selected matrix,
not a certification that every operational form of S1-S20 has been tested.

| Scenario | Executable evidence | Boundary verified |
|---|---|---|
| S1/S2: no stock or failed payment | [OrderFlowTests](../src/test/java/com/ecommerce/ordering/OrderFlowTests.java) | Rejected/cancelled orders release their reservations and coupon effects |
| S4/S5: duplicate participant replies or reservations | [LateReplyTests](../src/test/java/com/ecommerce/ordering/LateReplyTests.java), [StockReservationTests](../src/test/java/com/ecommerce/inventory/StockReservationTests.java) | Repeated inputs do not repeat saga or stock effects |
| S6: cancellation overlaps payment | [CancellationTests](../src/test/java/com/ecommerce/ordering/CancellationTests.java) | Cancellation/payment orderings compensate correctly; a shipped order is not silently cancelled |
| S7: last-unit contention | [LastUnitTests](../src/test/java/com/ecommerce/inventory/LastUnitTests.java), `exactlyOneOfEightOrdersGetsTheLastUnit` | Eight callers queued behind the same row lock produce one holder and seven rejections |
| S9: unavailable broker | [KafkaUnavailableTests](../src/test/java/com/ecommerce/platform/messaging/KafkaUnavailableTests.java) | Relay backoff retains events and never parks them at the internal-handler attempt limit |
| S9: broker recovery | [KafkaRelayTests](../src/test/java/com/ecommerce/platform/messaging/KafkaRelayTests.java), `aBrokerOutageBuffersEventsAndRecoveryDeliversTheOriginalIdsInOrder` | A real broker stop/restart preserves event IDs and per-key order through catch-up |
| Relay send/commit failure | [KafkaRelayTests](../src/test/java/com/ecommerce/platform/messaging/KafkaRelayTests.java), `aCrashBetweenSendingAndCommittingSendsTheSameMessageAgain` | Failing the database update after a broker send redelivers the same message ID |
| S11: consumer commit/acknowledgement failure | [NotificationKafkaTests](../src/test/java/com/ecommerce/notifications/domain/NotificationKafkaTests.java), `aConsumerRestartAfterDatabaseCommitBeforeAcknowledgementDoesNotRepeatTheEffect` | A fresh consumer redelivers an uncommitted offset without replacing the original notification or task |
| Transaction rollback and retry | [IntegrationInboxTests](../src/test/java/com/ecommerce/platform/messaging/IntegrationInboxTests.java), [DispatcherTests](../src/test/java/com/ecommerce/platform/messaging/DispatcherTests.java) | Inbox markers and database effects roll back together; a retry can apply them |
| Poison message and unavailable DLT | [NotificationKafkaTests](../src/test/java/com/ecommerce/notifications/domain/NotificationKafkaTests.java) | Original binary keys/values survive recovery; failed DLT publication does not advance the source offset |
| Repair and operator replay | [NotificationKafkaTests](../src/test/java/com/ecommerce/notifications/domain/NotificationKafkaTests.java), `aFailedDatabaseEffectCanBeReplayedFromDeadLettersAfterRepair` | Failed effects leave no rows; replaying the valid DLT record twice after repair creates one notification/task |
| S12: stale stock or order event | [AvailabilityHintTests](../src/test/java/com/ecommerce/catalog/AvailabilityHintTests.java), [NotificationTests](../src/test/java/com/ecommerce/notifications/domain/NotificationTests.java) | Older sequences cannot regress the stock projection or enqueue a misleading earlier milestone |
| S17: repeated checkout | [PlacementTests](../src/test/java/com/ecommerce/ordering/PlacementTests.java), `theSameKeyReplaysTheOrderAndAnotherRequestWithItIsRefused` | Same-key/same-body placement replays; key reuse for another body is refused |
| S18: refund failure | [RefundTests](../src/test/java/com/ecommerce/ordering/RefundTests.java) | Refund failure is recorded and alerted instead of represented as success |
| S20: task lease expiry | [TaskSchedulerTests](../src/test/java/com/ecommerce/platform/tasks/TaskSchedulerTests.java) | Expired work is reclaimed; the worker with the old lease cannot overwrite the result |
| SMTP unavailable | [NotificationTests](../src/test/java/com/ecommerce/notifications/domain/NotificationTests.java) | Email failure leaves work retryable outside consumption; exhaustion is explicit FAILED state |
| Event-storage failure | [OrderIntegrationEventTests](../src/test/java/com/ecommerce/ordering/OrderIntegrationEventTests.java) | Failed outbox storage rolls back order placement and its commands |

### Limits of this evidence

- Broker recovery uses one real embedded broker, not replicated-broker failover,
  network partition tolerance, or quorum durability.
- Consumer crash windows are injected at transaction/acknowledgement boundaries;
  these checks do not forcibly terminate the application JVM or PostgreSQL.
- Payment and carrier behavior in the selected Java suites uses their existing
  test adapters. Real-gateway compose demos remain separate CI evidence from P1.
- SMTP retry uses a failing delivery adapter. Mailpit's happy-path delivery is
  separate evidence; neither makes external email delivery exactly once.
- Live database outages, real scheduler recovery, flash-sale overload, and cloud
  disaster recovery remain outside this portfolio subset of the full plan.

## P3 implementation design

Start with database-backed queue gauges owned by the module that owns each table.
Outbox pending counts include parked rows, which can block later messages for the
same aggregate; parked is a subset, not an additional backlog to sum. Age uses
PostgreSQL's `now()` and the oldest undelivered row, with zero for an empty queue.
Only fixed transport/status labels are allowed. Order IDs, customer IDs, message
IDs, payloads, and exception text never become metric labels.

Prometheus is exposed on the existing internal management port, never the public
application port. The local observability stack will scrape that endpoint; a
deployment must restrict the management network. Queue metrics describe current
database state rather than cumulative delivery counters. With multiple instances,
dashboards must use `max`, not `sum`, for gauges reading the shared database.

The first check creates pending, parked, and delivered outbox rows and verifies
their exact gauges and age. An HTTP scrape must work only on the management port
and must not contain the test's private aggregate ID or payload.

Orders expose one current-state gauge per status and count/age gauges for overdue
unfinished processes. Tasks expose state counts, expired leases, and age since a
pending task became due; future work is excluded from overdue age. Notifications
expose PENDING/SENT/SKIPPED/FAILED intent counts and the oldest pending age. Each
module reads only its own schema. These gauges query live data at collection time;
SQL failures are not converted to a misleading zero. Their scrape cost and the
deployment's scrape timeout must be considered before scaling this portfolio
configuration.

Order milestone logs are written after the state/outbox transaction commits.
The placement entry links HTTP `request_id` to the order's `correlation_id`.
Internal messages, notification consumption, and send tasks carry scoped message
or task IDs and restore the caller's MDC afterward. Only canonical UUIDs are
accepted as order correlation IDs; non-order correlation strings are omitted
from logging, not modified in stored messages. Event bodies and customer contact
details are not included in these new logs. This is searchable log correlation,
not a claim of OpenTelemetry traces across sibling services.

## Running P3

Use the optional observability overlay in addition to the P1 stack:

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-observability.sh
```

The read-only dashboard is at `http://localhost:3001/d/ecommerce-operations`, and
Prometheus alerts are at `http://localhost:9090/alerts`. The overlay explicitly
replaces the app's port bindings to make management port 8081 loopback-only.
Docker Compose 2.24.4+ is required. It adds 768 MiB of container memory limits;
these are local demonstration limits, not sizing recommendations.

The demo exercises the existing order workflow including a stock rejection, then
checks live database-state metrics, drained Kafka outbox, sent notifications,
HTTP traffic, histogram buckets, dashboard provisioning, rule loading, and
committed-order logs. [The runbook](portfolio-runbook.md) documents read-only
diagnosis, alert thresholds, dependencies, recovery, and the remaining limits.
[ADR-029](decisions/ADR-029-portfolio-observability.md) records the chosen scope.

CI runs the Java regressions, `promtool` alert tests, and the live compose demo.
Rules are displayed in Prometheus; no paging channel or Alertmanager is claimed.
The Kafka alert describes recent DLT recovery activity, not unresolved DLT depth.

## P4 implementation design

Preserve the existing resource-server, ownership, role, idempotency, and webhook
checks. Start at observable data boundaries: authentication failures must not log
the exception's token-bearing details, even with debug logging enabled. The first
regression injects a token/email into an authentication exception and verifies
the existing 401 problem response and challenge remain unchanged while the log
contains only a bounded exception type.

Follow with application rate limits, a focused review of stored/logged failure
details and secrets, and CI scanning of the shipped jar and application image.
Record limits and accepted local-only configurations explicitly; this milestone
does not claim a completed OWASP certification or distributed edge protection.

Rate limiting uses Bucket4j with bounded local client state and independent API
and webhook ceilings. The exact quotas, authentication ordering, peer identity,
cache-eviction behavior, and deployment limitations are recorded in
[ADR-030](decisions/ADR-030-application-rate-limits.md). The first core checks use
a controlled monotonic clock for refill/Retry-After, concurrent consumption,
independent lanes, cache bounds, and fixed-cardinality metrics.

HTTP tests explicitly enable small quotas and prove the security-filter ordering:
invalid tokens consume the early peer quota; per-subject writes stop before MVC
and idempotency; read requests, health probes, and management remain available;
webhook authentication has its own lane; forwarded headers and encoded path
characters do not bypass the peer limit. Ordinary integration fixtures turn the
limiter off so unrelated domain tests do not compete for a shared loopback quota.

Stored retry errors and operational failure logs retain only the root exception
class and, for SQL errors, a validated five-character SQLSTATE. Exception text,
SQL statements, failed rows, and nested/suppressed messages are not copied. Task
IDs, message IDs, retry counts, and P3 correlation provide the diagnostic link.
The regression injects a fake token/email into a real task failure and verifies
retry remains possible without retaining those values in `last_error` or logs.

The initial binary-artifact vulnerability scan found 14 HIGH/CRITICAL entries
across 150 packaged dependencies. Use Tomcat 11.0.25, Jackson 3.1.7 and 2.21.7
BOM overrides and LZ4 1.11.4 rather than changing framework major versions.
Scanning a jar directory with a source-only command is not evidence: the check
must report a nonempty package inventory from `trivy rootfs` or an image scan.

## Running P4 checks

[Portfolio security](portfolio-security.md) records quotas, local-only credentials,
diagnostic redaction, scanner policy, and deployment responsibilities. The limits
are enabled by default in the application. Dedicated HTTP and controlled-clock
tests cover rejection, refill, lane separation, bypass attempts, safe error
responses, configuration validation, and unaffected probes.

```sh
./gradlew build
node scripts/check-security-report.mjs --self-test
bash scripts/check-security.sh jar
bash scripts/check-security.sh secrets
bash scripts/check-security.sh image ecommerce-order-system-app
```

The scan commands require Trivy 0.76.0 on PATH (or `TRIVY=/path/to/trivy`), Node
18+, and a freshly built image for the last command. CI installs the pinned
scanner automatically. Dependency/image reports are retained for seven days;
secret reports are private temporary files and are not uploaded. A passing scan
is evidence for its advisory database snapshot, not a permanent security claim.

## P5 implementation design

Use k6 for measured HTTP traffic, with separate catalogue and checkout-contention
scenarios. A run owns a unique SKU and idempotency-key prefix. Setup work and
authentication are excluded from the reported scenario latency/rate. Tokens stay
in the load generator's memory; published results contain only aggregate metrics,
fixture identifiers, environment details, and invariant outcomes.

The normal scenario generates a bounded constant arrival rate of catalogue list
and detail reads. The contention scenario prepares distinct quotes before the
measured run, then places one order per buyer concurrently and retries each with
the same byte-identical body/key. It classifies HTTP acceptance, throttling,
unexpected responses, payment-ready orders, and stock rejections separately.
An accepted HTTP 202 is not counted as a completed order outcome. Each accepted
order must reach AWAITING_PAYMENT or REJECTED within a bounded polling window.

The first discriminating run is deliberately small. Once it works, increase only
within the local machine's resource budget and record the actual settings. For
the contention fixture, require exactly as many held one-unit orders as the SKU
has units, all other orders rejected for stock, identical idempotent responses,
and warehouse availability zero. A read-only database audit checks stock,
reservation, movement, order, and replay-row invariants for the run's SKU.

Keep P4 rate limits enabled and report their effective values. Unexpected 429s,
timeouts, incorrect outcomes, missing report data, and dropped scheduled work
fail the evidence gate rather than being removed from latency statistics. The
benchmark uses the local payment adapter and does not measure successful payment
or carrier processing, a 100,000-user flash sale, or cloud capacity. Existing
deterministic last-unit race tests remain the correctness backstop.

The runner records physical host/Docker resources, runtime JVM and database
versions, application image IDs, memory limits, worker concurrency, rate-limit
settings, fixture size, harness hashes, and point-in-time resource snapshots.
The k6 process is bounded to one CPU and 256 MiB. Scenario elapsed time excludes
setup/teardown; the raw k6 overall duration is retained separately. Contention
uses distinct quotes/orders split across the two local demo customers, not a
claim of a large independent-customer population. The runner resets only their
demo carts and leaves each unique fixture's records for audit and normal expiry.

CI executes small browse and contention runs to detect harness and correctness
regressions. CI timings are not substituted for the separately recorded local
60-second observations. Missing samples, failed checks, wrong quotas/outcomes,
unresolved orders, and inconsistent SQL audits fail the report policy.

Measured commands, environment, full reports, correctness audits, and the retained
slower contention tail are in [portfolio-performance.md](portfolio-performance.md).
Only short smoke profiles run in CI; local measurements remain identified by
their timestamps, image IDs, and harness hashes.

## P6 implementation design

Provide one reviewer guide that distinguishes shipped behavior from the full
roadmap, links each important claim to executable evidence, and gives a short
walkthrough using the existing demos. Correct stale README claims instead of
rewriting the original roadmap. A recording or transcript must come from a real
run and must not expose bearer tokens or real customer data.

Verify the walkthrough from a clean Git checkout with a distinct compose project
and newly created data volumes. Reuse neither the current checkout's generated
files nor its populated database. Build caches may be reused and must be reported
as such. Preserve existing project volumes and unrelated containers; on the small
local VM, temporarily stop only this project's running services if needed, then
restore them after the isolated rehearsal. Record the exact source commit,
commands, image, results, and any machine-specific workaround. CI remains a
separate fresh-runner verification of the public source and demos.

## Running P6

Start with [the reviewer guide](portfolio-guide.md), which links the architecture,
decisions, named tests, measured reports, security scope, and operational limits.
With the portfolio/observability stack already running:

```sh
bash scripts/demo-portfolio.sh
node scripts/check-portfolio.mjs
```

The [real terminal recording](recordings/2026-10-15/walkthrough.cast),
[derived transcript](recordings/2026-10-15/transcript.txt), and
[clean-checkout evidence](recordings/2026-10-15/fresh-checkout.json) come from public
commit `163b14a` with new compose volumes. The recording runs the two existing
demo scripts that the wrapper composes; CI now tests the wrapper itself.
Recording integrity and reviewer links are checked in CI. This is a terminal
demonstration, not a narrated video or a recording of browser dashboards.

## Evidence log

| Date | Slice | Evidence | Result |
|---|---|---|---|
| 2026-10-15 | P6 CI and milestone closeout | Commit `a972f7b`; [GitHub Actions run 38620997376](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38620997376) | Full build/security/evidence gates, reviewer walkthrough, load smoke, and real-gateway ecosystem passed; all six portfolio items complete |
| 2026-10-15 | P6 reviewer path | Corrected README scope, reviewer guide, and `bash scripts/demo-portfolio.sh` against the existing portfolio stack | Passed; original roadmap unchanged |
| 2026-10-15 | P6 clean clone | Fresh public `163b14a` clone, clean before/after, distinct compose project and new volumes; products/orders/notifications began at 0/0/0 and ended at 2/3/6 | Both existing demos passed; Docker/build caches reused |
| 2026-10-15 | P6 recording | Actual 41.1-second asciicast, derived transcript, SHA-256 and timestamp checks, 113 local reviewer links, no recognized secret findings | Passed; not a narrated/browser video |
| 2026-10-15 | P6 recovery | First attempt in an unshared macOS temporary directory could not bind configuration files; retry in Docker-shared home succeeded; original container IDs and data-volume names verified after both restorations | No existing volumes deleted; unrelated ride-hailing database stayed running |
| 2026-10-15 | P6 regression gate | `./gradlew build` successful with unchanged Java tests up to date; walkthrough and evidence validator freshly executed | Passed; fresh full build, security scans, and wrapper verification pending CI |
| 2026-10-15 | P5 CI | Commit `163b14a`; [GitHub Actions run 38610362785](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38610362785) | Build/security gates, load smoke, portfolio demo, and real-gateway regressions passed; P5 complete |
| 2026-10-14 | P5 workload gates | Short browse/contention smoke runs, validator negative tests, and four archived report sets with environment snapshots and SQL audits | Passed; no failed checks, throttling, or missing outcomes |
| 2026-10-14 | P5 measured reads | Two 60-second runs: 20.03 and 40.03 requests/s; p95 6.02 and 4.25 ms; 1,202 and 2,402 measured requests | Passed; small warm local dataset, not maximum capacity |
| 2026-10-14 | P5 measured contention | 24 orders for five units: 5 held/19 rejected; 24 for one unit: 1 held/23 rejected; 24 exact replays per run and all SQL invariants valid | Passed; slower five-unit placement p95 of 1,608.26 ms retained |
| 2026-10-14 | P5 regression gate | `./gradlew build` successful with unchanged Java tests up to date; source/report secret scan has no findings; original roadmap and failure analysis unchanged | Passed; fresh full build and load smoke pending CI |
| 2026-10-14 | P4 CI | Commit `50f7629`; [GitHub Actions run 38603577647](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38603577647) | Build/security gates, portfolio container, and real-gateway ecosystem passed; P4 complete |
| 2026-10-14 | Planning | Separate scope, estimates, acceptance criteria, and deferred work recorded | P1 started; implementation not yet verified |
| 2026-10-14 | P1 core | Full local `./gradlew build`; 956 tests including producer contracts, inbox rollback, SMTP failures, and real-broker DLT failures | Passed; compose demonstration and CI pending |
| 2026-10-14 | P1 demo | Container image built; portfolio compose stack healthy; `bash scripts/demo-events.sh` completed placement/replay/cancellation, stock hints, and two Mailpit emails | Passed; CI pending |
| 2026-10-14 | P1 CI | Commit `872d073`; [GitHub Actions run 38580017670](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38580017670) | Passed; P1 complete |
| 2026-10-14 | P2 selected evidence | `./gradlew portfolioFailureTest`: 124 tests in 16 suites; JUnit XML confirms all three new fault regressions executed | Passed, none skipped |
| 2026-10-14 | P2 regression gate | `./gradlew build`: 959 tests in 101 suites; original phase plan and failure analysis verified byte-identical to the phase 8 baseline | Passed, none skipped; CI pending |
| 2026-10-14 | P2 CI | Commit `272155e`; [GitHub Actions run 38583313319](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38583313319) | Build, portfolio demo, and real-gateway demo passed; P2 complete |
| 2026-10-14 | P3 Java gate | `./gradlew build`: 975 tests in 104 suites, including queue/state metrics, management access, MDC cleanup, and commit-only correlation logs | Passed, no failures or skips |
| 2026-10-14 | P3 alerts | Prometheus 3.15.0 `promtool test rules`: eight alerts with healthy, threshold, hold-time, independent failure, counter, NaN/missing-data, and recovery cases | Passed |
| 2026-10-14 | P3 live demo | `bash scripts/demo-observability.sh`: rejected/cancelled orders, real Prometheus state and HTTP metrics, sent email, loaded rules, dashboard provisioning, and correlated logs | Passed |
| 2026-10-14 | P3 dashboard | Playwright/Chrome at 1440x1000 and 390x844; all 18 PromQL expressions return series; charts and lower lazy-loaded panels render; no JavaScript errors or mobile page-width overflow | Passed; screenshots inspected locally |
| 2026-10-14 | P3 local sizing | Grafana exceeded the initial 256 MiB limit during rendering. With 512 MiB, Go memory target 320 MiB, and automatic plugins disabled, rendering passed; sampled usage approximately 450 MiB, Prometheus 110 MiB | Local observation, not production sizing; CI pending |
| 2026-10-14 | P3 CI | Commit `55114a9`; [GitHub Actions run 38592878291](https://github.com/Deepika-1201/ecommerce-order-system/actions/runs/38592878291) | Java build, alert tests, portfolio demo, and real-gateway regressions passed; P3 complete |
| 2026-10-14 | P4 regression gate | `./gradlew build`: 995 tests in 110 suites, including HTTP limits, defaults/binding, safe diagnostics, secret masking, and generated 429 responses | Passed, no failures or skips |
| 2026-10-14 | P4 jar scan | Trivy 0.76.0 binary-artifact scan; 150 Java packages inventoried; initial 14 HIGH/CRITICAL findings fixed with compatible patches | Zero HIGH/CRITICAL findings in rebuilt jar |
| 2026-10-14 | P4 image scan | Exact rebuilt application image; 150 Java and 106 OS packages inventoried; unused vulnerable Pebble supervisor removed | Zero HIGH/CRITICAL findings, including unfixed |
| 2026-10-14 | P4 source and policy | Recognized-secret scan plus report-policy checks for missing inventory, fixable findings, and redacted secret errors | Passed; no recognized secret matches; Git history and unknown secret formats not claimed |
| 2026-10-14 | P4 live workflows | Scanned image started as the non-root app; event/Mailpit and observability demos ran with rate limits enabled | Passed; CI pending |