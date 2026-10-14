# ADR-029: Database-state metrics and correlated logs for the portfolio

Status: Accepted, 2026-10-14 (portfolio milestone P3).

## Context

The portfolio needs evidence that stalled orders, blocked queues, and failed
notifications can be found and diagnosed. The complete roadmap additionally
requires tracing across independently deployed systems; those deployments are
outside this smaller milestone.

## Problem

Counting changes before a transaction commits creates misleading outcomes after
a rollback. Labelling metrics with order IDs creates unbounded time series and
may expose customer information. Summing shared-database gauges across replicas
counts the same work repeatedly.

## Options considered

1. In-memory outcome counters in every state-changing method.
2. Module-owned database gauges, commit-only milestone logs, and an optional
   Prometheus/Grafana stack.
3. A complete tracing, log-aggregation, metrics, and paging platform immediately.

## Decision

Use option 2 for P3. Each module reads its own durable state. Gauges report current
states, not lifetime event counts. Pending outbox counts include parked rows;
pending task age excludes future-scheduled work. Fixed state, transport, and
consumer labels keep cardinality bounded. Dashboard queries use `max` for shared
database gauges and `sum` only for independent per-process counters and pools.

Order milestone logs run after commit and link the API request ID to the order
UUID. Scoped message/task context restores the previous MDC after work. No event
payload, customer contact, or free-form correlation string is added to these logs.

Prometheus exposes the metrics on the management port. The optional local overlay
binds its management, Prometheus, and Grafana host ports to loopback. Grafana is
anonymous read-only for the local demo. Alert rules are executable `promtool`
tests, including invalid/missing measurements and the first DLT counter increment.

## Trade-offs

Live SQL gauges add collection cost and are not an atomic snapshot across all
modules. This is suitable for the measured portfolio footprint, not a claim of
unbounded scale. Growing data volumes may need indexed aggregate queries or a
bounded cached snapshot. Failed gauges can become NaN; an invalid-metrics alert
must not interpret that as a healthy empty queue.

## Consequences

Logs can be correlated locally but are not OpenTelemetry distributed traces.
The stack displays alerts but has no Alertmanager receiver or external paging.
DLT counters report recent recovery activity, not unresolved DLT queue depth.
Production deployments must secure management access and supply authentication,
retention, log access controls, and a real notification channel.

## Revisit when

Collection becomes a meaningful database load, modules are extracted, a
production environment is provisioned, or external paging/tracing is required.