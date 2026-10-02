# ADR-009: Inventory reservation with conditional updates and holds

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Domain model §2](../domain-model.md#2-aggregates-and-invariants), [architecture §11.3 and §16](../architecture.md#16-scalability), [ADR-002](ADR-002-payment-gateway-integration.md), [ADR-007](ADR-007-saga-orchestration.md)

## Context

- Overselling is forbidden (FR-INV4). Orders reserve all their lines or none (FR-INV2).
- A flash sale sends about 1,700 attempts per second at one SKU (NFR-2).
- A gateway payment that is still processing at its expiry can succeed up to 30 minutes later (the gateway's grace). A late success after expiry or cancellation is refunded by the gateway (`AUTO_REFUND`).
- The Job Scheduler can be late or down, so correctness cannot depend on a timer firing on time.

## Problem

How is stock reserved without overselling under contention, and how long is a hold kept, given the gateway's timing?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Optimistic locking: read, check, update with `version`, retry on conflict | No locks held while reading | Under contention most attempts conflict and retry: a retry storm in a flash sale |
| Pessimistic: `SELECT … FOR UPDATE`, check, update | Correct | Holds the lock for longer than needed; two round trips |
| **Atomic conditional update and reservation records** | One statement decides; the lock is held only for the update and commit; reservations record exactly who holds what | Hot rows still serialize (handled by admission control) |
| Distributed lock (Redis, ZooKeeper) | — | Adds a failure mode for nothing: the database row already is the lock |
| Redis counters as the gate, PostgreSQL as the record | Very fast decrements | Two sources of truth to reconcile; Redis durability; no all-or-nothing across SKUs |
| A single writer per SKU (Kafka partition per SKU) | No locks at all; scales hot SKUs | Multi-SKU orders need a saga across partitions; worth it only if the database cannot keep up (V3+) |

## Decision

**Atomic conditional updates on `StockItem`, plus a `Reservation` record per order** (proposed).

- **Reserving.** One transaction:
  1. Insert the reservation row (order id is unique). A duplicate command gets the recorded outcome.
  2. For each line, in SKU order: `UPDATE … SET reserved = reserved + :qty WHERE … AND on_hand - reserved >= :qty`.
  3. If a line updates no row, release expired holds on that SKU in the same transaction and retry the line once.
  4. If it is still short, roll back and record `REJECTED`.

  Under READ COMMITTED, a waiting update re-checks its condition against the committed row, so the last unit cannot be taken twice. Lock waits on stock rows are capped at 500 ms.
- **Hold expiry** = payment window (15 minutes; 5 for flash-sale SKUs) + the gateway's processing grace (30 minutes) + 5 minutes of margin.
  - **Normal path:** holds are released as soon as the gateway reports the payment failed, expired or cancelled (the webhook). Abandoned checkouts therefore return their stock when the payment window ends, while payments still processing keep their stock until they can no longer succeed.
  - **Backstop:** the deadline sweep polls the gateway at hold expiry.
  - **On demand:** a competing reservation reclaims expired holds, so a late or down scheduler never blocks sales.
- **Lost hold after payment.** A success can only find its hold gone if webhooks were lost for the whole hold and another order reclaimed the stock in the minute before the sweep. `CommitReservation` then answers `ReservationLost`; the saga re-reserves or refunds ([ADR-007](ADR-007-saga-orchestration.md)).
- **Counter changes:**
  - commit: none (a status change);
  - release: `reserved` decreases;
  - handover: `on_hand` and `reserved` decrease;
  - receipt or restock: `on_hand` increases.
- **Flash sale:**
  - a waiting room in front of placement;
  - one unit per customer;
  - a per-instance sold-out flag that lives 1 second and can only reject early, never accept;
  - the shorter payment window.

  The waiting room's mechanism is chosen in phase 16 with load-test evidence. Striped stock rows (a SKU's units split across several rows) are added only if load tests show the single row cannot keep up.
- **No distributed locks** and **no Redis gate** in V1.

## Trade-offs

- A hot row serializes at about 200–500 reservations/s, because a synchronous standby makes each commit cost 2–5 ms. Admission control shapes the load to that rate; it does not raise it.
- Holds for payments stuck in processing can last up to 50 minutes. That is the price of never refunding a payment that succeeded within the gateway's rules.
- Reserving all lines in one transaction ties this design to stock living in one database. Partitioned stock would need per-line reservations and a saga, which is the single-writer option, kept for V3+.

## Consequences

- Concurrency tests (phase 5) run N parallel reservations for the last unit and assert exactly one success and no negative availability.
- Inventory's events (`stock.level_changed`) feed eventually consistent availability hints; checkout never reads them.
