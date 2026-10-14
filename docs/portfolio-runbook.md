# Portfolio operations runbook

This runbook covers the P3 local footprint, not production readiness. See
[the milestone](portfolio-milestone.md) and [ADR-029](decisions/ADR-029-portfolio-observability.md).
Do not post SQL results or application logs containing customer data publicly.
The queries below intentionally omit event bodies, contact details, and raw errors.

## Start and verify

Docker Compose 2.24.4 or newer is required for the explicit management-port override.

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml
docker compose up --build --detach --wait --wait-timeout 240
bash scripts/demo-events.sh
```

- Grafana: <http://localhost:3001/d/ecommerce-operations>, local read-only access.
- Prometheus alerts: <http://localhost:9090/alerts>.
- Mailpit: <http://localhost:8025>.
- Application: <http://localhost:8080>; management: <http://localhost:8081>.

Prometheus scrapes every 15 seconds. Request rates/latency need traffic and at
least two scrapes. Missing data is not zero traffic or a healthy queue. State
counts are gauges: do not apply `rate()` or infer historical order throughput from
them. Multiple app instances read the same database, so aggregate those gauges
with `max`, not `sum`. Pool sizes and counter rates are per-process and can sum.
The local Prometheus retains at most 24 hours or 128 MB, whichever is reached first.

Validate the rules without starting this stack:

```sh
docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/deploy/observability:/work:ro" -w /work \
  prom/prometheus:v3.15.0 test rules alert-tests.yml
```

## Scrape unavailable

Signals: `EcommerceScrapeUnavailable` or `EcommerceMetricsInvalid`.

1. Check `docker compose ps` and `curl -fsS localhost:8081/actuator/health/readiness`.
2. Check `curl -fsS localhost:8081/actuator/prometheus` and Prometheus's Targets page.
   A failed database collection can produce NaN even if HTTP returns 200.
3. Check PostgreSQL readiness and database-pool waiting connections. Do not widen
   public management access to work around a scrape-network problem.
4. Fix the failing dependency/configuration, then require healthy scrapes and
   valid backlog measurements before declaring recovery.

## Order flow

Signal: `EcommerceOrdersOverdue` after the oldest unfinished deadline has been
overdue for more than five minutes continuously for two minutes.

Find the order UUID from the API response. Set `ORDER_ID` to that UUID, then search
the API/worker logs without requesting customer payloads:

```sh
docker compose logs --no-color app | grep -F "$ORDER_ID"
```

`order_milestone_committed` links request ID, order ID, status, and saved sequence.
`notification_event_processed` means the database receive boundary succeeded,
not that email was sent. `task_completed` records a completed non-recurring task.
Message IDs and task IDs let an operator follow work sharing the order ID.

For a read-only database diagnosis, open `docker compose exec postgres psql -U ecommerce`
and set the psql variable `order_id` to the affected UUID:

```sql
SELECT o.id, o.status, o.refund_status, p.step, p.attempts, p.deadline_at,
       greatest(extract(epoch FROM now() - p.deadline_at), 0) AS overdue_seconds
FROM ordering.orders o JOIN ordering.order_processes p ON p.order_id = o.id
WHERE o.id = :'order_id'::uuid;

SELECT message_id, destination, type, sequence, attempts, created_at,
       next_attempt_at, parked_at, delivered_at
FROM platform.outbox
WHERE aggregate_id = :'order_id' ORDER BY id;
```

Do not manually advance order statuses or mark outbox rows delivered. Resolve the
dependency and let the saga retry/compensate. A requested refund is not a settled
refund, even when an order already has its final cancelled/returned status.

## Outbox backlog

Signals: `EcommerceOutboxStalled` (oldest undelivered row over 120 seconds for two
minutes) or `EcommerceOutboxParked` (a parked row for 30 seconds).

```sql
SELECT destination, count(*) AS pending,
       count(*) FILTER (WHERE parked_at IS NOT NULL) AS parked,
       min(created_at) AS oldest
FROM platform.outbox WHERE delivered_at IS NULL GROUP BY destination;
```

Parked rows are included in pending and can block their own aggregate's later
rows. They do not block unrelated aggregates. Kafka transport failures remain
retryable without parking. Check Kafka health, worker roles, and outbox logs.
Internal parked work requires reviewing the fault before using the platform's
operator redrive capability; there is no public unauthenticated redrive endpoint.
After recovery, check that the backlog drains and business effects remain unique.

## Scheduled tasks

Signal: `EcommerceTasksNeedAttention` after one minute of overdue pending work,
expired running leases, or a DEAD task. The pending-age threshold is 120 seconds.

```sql
SELECT id, type, status, attempts, max_attempts, run_at, lease_until, correlation_id
FROM platform.scheduled_tasks
WHERE status = 'DEAD'
   OR (status = 'PENDING' AND run_at < now() - interval '2 minutes')
   OR (status = 'RUNNING' AND lease_until < now())
ORDER BY run_at LIMIT 50;
```

Future-scheduled tasks are not late. Check the worker role and the relevant
dependency. Expired leases are reclaimed by the runner; the old owner cannot
overwrite the new result. DEAD work requires an operator decision, not an endless
blind retry. Do not bypass task fencing by editing the task's lease.

## Notifications

Signal: `EcommerceNotificationsNeedAttention` for pending email older than five
minutes or FAILED delivery, sustained for one minute.

```sql
SELECT id, order_id, kind, status, attempts, last_error, created_at, finished_at
FROM notifications.notifications
WHERE status IN ('PENDING', 'FAILED') ORDER BY created_at LIMIT 50;
```

Check Mailpit/SMTP readiness and the matching send task. PENDING work retries with
backoff; after 20 attempts delivery is FAILED. `missing_recipient` is SKIPPED, not
a failed SMTP connection. Contact details are looked up inside the Customer
module and are intentionally absent from Kafka and these diagnostic queries.

SMTP acceptance and the database success mark cannot be one transaction. A crash
between them can resend the same email. Review captured messages and the stable
notification ID before any manual resend. Replaying the original event does not
reset a FAILED unique notification; a failed-delivery resend remains a deliberate
operator workflow, not an exposed portfolio API.

## Kafka recovery

Signal: `EcommerceKafkaRecoveryActivity`, a new DLT publication or a failed DLT
publication in the last five minutes. Counters are registered before the first
event. This is recent recovery activity, not a gauge of unresolved DLT records.

Follow [the milestone's dead-letter recovery procedure](portfolio-milestone.md#dead-letter-recovery).
Preserve raw keys, values, message IDs, and original offsets. Fix the cause first.
If publishing to the DLT fails, the source offset stays uncommitted; do not force
it forward to clear consumer lag. After a reviewed replay, require one database
effect and the expected committed offset. The P2 tests demonstrate these checks.

## Local failure demonstration

On a disposable portfolio stack, pause SMTP delivery while leaving the broker,
API, and workers available:

```sh
docker compose stop mailpit
```

Place/cancel a test order using the existing demo's API steps. The order can still
finish; its notification and send task remain pending. Inspect their metric
values, generated IDs, and redacted failure state. Restart Mailpit:

```sh
docker compose start mailpit
```

Allow the task's recorded backoff to elapse. Confirm the notification becomes SENT,
Mailpit captured it, and the pending count returns to its earlier value. The
automated Java regression drives retry time explicitly; this live demonstration
uses normal task timing. Do not run failure demonstrations against production.

## Limits

Alerts appear in Prometheus; this milestone does not configure Alertmanager or
send pages. Logs are correlated but not centrally stored or traced across sibling
services. Management access is unauthenticated on the local internal network and
must be restricted in a deployment. Database gauges perform live queries; measure
scrape overhead before increasing data volume or shortening the interval.