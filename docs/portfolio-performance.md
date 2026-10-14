# Portfolio performance evidence

Measured locally on 2026-10-14 for P5 of [the portfolio milestone](portfolio-milestone.md).
These are bounded workload observations, not a capacity certification or evidence
of the full roadmap's 100,000-user flash sale. No application throughput claim is
derived from a short contention burst.

## Reproduce

Requirements: Docker Compose, Node 18+, the local portfolio stack, and its demo
identity users. k6 2.3.0 runs in a container bounded to one CPU and 256 MiB.
The harness intentionally refuses nonlocal API/identity endpoints and real
payment-gateway configuration. It creates real local orders; it is not a
production load-test command.

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml
docker compose up --build --detach --wait --wait-timeout 240

# Short correctness and harness checks, also run in CI.
DURATION_SECONDS=5 RATE=2 VUS=2 bash scripts/run-load.sh browse
BUYERS=8 STOCK=2 bash scripts/run-load.sh contention

# The measured local profiles below, run sequentially.
RATE=10 DURATION_SECONDS=60 VUS=8 bash scripts/run-load.sh browse
RATE=20 DURATION_SECONDS=60 VUS=8 bash scripts/run-load.sh browse
BUYERS=24 STOCK=5 bash scripts/run-load.sh contention
BUYERS=24 STOCK=1 bash scripts/run-load.sh contention
```

Each browse iteration performs one category-filtered list read and one product
detail read, so `RATE=10` targets about 20 requests/second. k6 reports dropped
iterations when the bounded VU pool cannot keep up; any drop fails the evidence
gate. A boundary iteration can make the count slightly larger than
`duration * rate * 2`.

Contention creates separate quotes before measurement and runs one placement per
VU. Orders are split between the two local customer accounts, not 24 independent
customer identities. Each VU retries the same request body and key, requires an
identical response, and polls its own order until AWAITING_PAYMENT or an
OUT_OF_STOCK rejection. Placement acceptance is not a claim of payment success.

The harness resets only the demo customers' carts to its new SKU and reuses or
creates a local Karnataka address. Unique products, orders, and audit data are
retained. Held reservations are released by normal cancellation/expiry; do not
expect a fresh database audit of an old run to show its historical held state.
Do not run the harness alongside another session using those demo carts.

## Environment

- Apple M2, 8 logical CPUs, 8 GiB host memory, macOS kernel 22.5.0.
- Docker 29.5.2 in a 4-CPU Linux VM with approximately 3.81 GiB memory.
- Java Temurin 25.0.4.1; PostgreSQL 17.11; Kafka 4.3.1.
- One combined API/worker application, 768 MiB container limit and
  `-XX:MaxRAMPercentage=75`; Hikari maximum 20, four dispatcher threads, four task
  runners. Application containers share VM CPU rather than having dedicated CPUs.
- PostgreSQL 384 MiB, Kafka 640 MiB, Keycloak 768 MiB, S3Proxy 256 MiB, Mailpit
  80 MiB, Prometheus 256 MiB, Grafana 512 MiB container limits.
- Kafka notifications, Mailpit, Prometheus, and Grafana remained enabled. One
  unrelated existing container remained running; no other project was stopped.
- The existing local database was not reset. The catalogue grew from 28 to 32
  products over these runs; this is a small dataset. Every run has an isolated SKU.
- P4 defaults remained enabled: 3,000 ordinary requests/minute per socket peer,
  120 writes/minute per authenticated principal, 6,000 webhook requests/minute
  per peer, 20,000 per process/lane, and a 10,000-entry limiter cache.
- Payments used the local fake. These tests do not measure gateway/PSP payment
  success, carrier processing, network WAN latency, or cloud infrastructure.

Runtime application source baseline was `50f7629`. The harness was being developed
in the worktree, recorded as dirty rather than described as a clean checkout.
Every report captures exact application/dependency image IDs and SHA-256 hashes
of the harness, SQL audit, validator, and environment collector. Full before/after
records accompany each result below.

## Catalogue results

Both runs lasted approximately 60 seconds, used eight bounded VUs, and had zero
unexpected responses, 429s, dropped iterations, or failed functional checks.
Latencies are HTTP request duration in milliseconds, excluding setup.

| Target | Measured requests | Achieved requests/s | Median | p95 | p99 | Maximum |
|---|---:|---:|---:|---:|---:|---:|
| 20 requests/s | 1,202 | 20.03 | 2.78 | 6.02 | 13.54 | 98.37 |
| 40 requests/s | 2,402 | 40.03 | 2.00 | 4.25 | 9.81 | 54.76 |

- [20 requests/s verified result](performance/2026-10-14/browse-20261014T173910-94025/verified.json),
  [raw summary](performance/2026-10-14/browse-20261014T173910-94025/summary.json),
  [before](performance/2026-10-14/browse-20261014T173910-94025/environment-before.json),
  [after](performance/2026-10-14/browse-20261014T173910-94025/environment-after.json).
- [40 requests/s verified result](performance/2026-10-14/browse-20261014T174018-94262/verified.json),
  [raw summary](performance/2026-10-14/browse-20261014T174018-94262/summary.json),
  [before](performance/2026-10-14/browse-20261014T174018-94262/environment-before.json),
  [after](performance/2026-10-14/browse-20261014T174018-94262/environment-after.json).

The higher-rate run being faster is not a scaling result. Run order, warmed JVM/
database state, and host scheduling are not controlled enough to attribute that
difference. Neither profile searches for the maximum sustainable rate.

## Contention results

All 24 placements in each run returned 202; all 24 exact retries returned the
same stored response. No unexpected response, throttling, or unresolved outcome
was removed from the results. Times below are milliseconds.

| Stock units | Payment-ready / stock-rejected | Placement p95 | Replay p95 | Outcome p95 | Last outcome |
|---|---:|---:|---:|---:|---:|
| 5 | 5 / 19 | 1,608.26 | 1,595.34 | 2,132.85 | 2,136 |
| 1 | 1 / 23 | 72.64 | 48.12 | 347.00 | 505 |

- [Five-unit verified result](performance/2026-10-14/contention-20261014T174126-94500/verified.json),
  [raw summary](performance/2026-10-14/contention-20261014T174126-94500/summary.json),
  [SQL audit](performance/2026-10-14/contention-20261014T174126-94500/audit.json),
  [before](performance/2026-10-14/contention-20261014T174126-94500/environment-before.json),
  [after](performance/2026-10-14/contention-20261014T174126-94500/environment-after.json).
- [Last-unit verified result](performance/2026-10-14/contention-20261014T174136-94717/verified.json),
  [raw summary](performance/2026-10-14/contention-20261014T174136-94717/summary.json),
  [SQL audit](performance/2026-10-14/contention-20261014T174136-94717/audit.json),
  [before](performance/2026-10-14/contention-20261014T174136-94717/environment-before.json),
  [after](performance/2026-10-14/contention-20261014T174136-94717/environment-after.json).

The raw metric named `settlement_ms` measures time from placement until the
awaiting-payment/rejected outcome, not financial settlement. The published
validator names it `outcome_latency_ms`. Polling is every 200 ms, so outcome
timing includes that observation granularity.

The five-unit run has a materially slower tail and is retained, not discarded.
Before/after resource snapshots cannot identify the cause of a two-second burst;
15-second monitoring can also miss short connection-pool/CPU pressure. No specific
CPU, lock, or database bottleneck is claimed without profiling. This is the next
path to investigate before tuning checkout concurrency. No correctness rule or
test was weakened to improve these numbers.

## Evidence gates and limits

The [report validator](../scripts/load/report.mjs) rejects absent/nonfinite
metrics, failed k6 thresholds, throttling, unexpected responses, dropped work,
missing audits, incorrect outcome counts, and inconsistent stock/replay links.
Its negative self-tests deliberately corrupt those cases. The
[read-only audit](../scripts/load/audit.sql) uses a repeatable-read transaction
and verifies stock counters, movement sum, one-unit reservations, outcome counts,
and unique stored replay responses for the run.

Throughput uses scenario-only request count divided by scenario elapsed time.
k6's raw overall counters can include setup; their `rate` values are not
substituted for the published rate. A burst's HTTP count includes polling and
retries and must not be described as sustained orders/second.

Reports are under `build/reports/load/<run-id>/`; the runner refuses to overwrite
a run. Tokens and contact details are not included in reports. CI runs only the
five-second browse and eight-buyer contention smoke checks and retains artifacts
for seven days. Their timings are not the local measurements above.

The existing deterministic [last-unit tests](../src/test/java/com/ecommerce/inventory/LastUnitTests.java)
queue callers behind a held database row lock. They remain the stronger check of
forced SQL-level overlap; starting HTTP VUs together is not proof that every
reservation transaction overlapped at exactly the same instant.

Four short, single-host observations establish neither statistical confidence
intervals, long-run soak behavior, large-catalogue performance, distributed quotas,
replicated broker durability, nor production capacity. Those remain future work.