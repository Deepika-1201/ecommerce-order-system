# ADR-007: Orchestrated saga for the order lifecycle

- **Status:** Accepted (2026-10-02); the lost-hold step amended by [ADR-009's amendment](ADR-009-inventory-reservation.md#amendment-2026-10-03-phase-5-lld)
- **Date:** 2026-10-02
- **Related:** [Order lifecycle](../order-lifecycle.md), [architecture §13](../architecture.md#13-saga-architecture), [ADR-002](ADR-002-payment-gateway-integration.md), [ADR-008](ADR-008-transactional-outbox.md), [ADR-009](ADR-009-inventory-reservation.md)

## Context

An order involves Inventory, Pricing (coupons), Payments (and through it, the gateway), and Fulfillment (and through it, a carrier).

- The gateway and the carrier are separate systems: they cannot join a database transaction.
- The workflow branches: cancellation in any pre-handover state, refunds after a lost hold, return to origin.
- It also waits: up to 50 minutes for a payment, days for delivery.

## Problem

How are the participants coordinated so that every order reaches a correct terminal state despite failures?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A. Two-phase commit (XA) | Atomic in theory | The gateway and carriers cannot take part; locks held across network calls and customer think-time; the coordinator becomes a single point of failure |
| **B. Orchestration** | One place holds the workflow, its deadlines and compensations; "where is order X stuck?" has one answer; participants stay ignorant of the workflow | The orchestrator is a central component that must itself be reliable |
| C. Choreography | No central component; participants react to events | The workflow is spread across listeners (Inventory reacting to PaymentFailed, Payments to ShipmentCancelled); cyclic dependencies; compensation logic is hard to see and test |

## Decision

**B, orchestration, for the order lifecycle**; **choreography for side reactions** such as notifications and availability hints (proposed).

- **OrderProcess.** One OrderProcess per order, in the Ordering module, persisted alongside the Order. Each step is one local transaction: update the process, update the order, write the next commands to the outbox, record the incoming message as processed.
- **Participants** only receive commands and reply; they never call the saga or each other.
- **The pivot is payment success.**
  - *Before it*, steps are compensatable: release stock, release the coupon, cancel the gateway payment.
  - *After it*, steps are retriable: commit the hold, book the shipment. They are retried until they succeed or a person intervenes. A carrier outage never triggers an automatic refund.
- **Cancellation is a new branch, not a rollback.** It starts from the current state ([architecture §13](../architecture.md#13-saga-architecture)).
- **Lost hold after payment.** If `CommitReservation` answers `ReservationLost`, the saga refunds and cancels (`STOCK_LOST_AFTER_PAYMENT`). `CommitReservation` has already tried to take the stock again, so the saga does not reserve again (amended in phase 5, see [ADR-009](ADR-009-inventory-reservation.md#amendment-2026-10-03-phase-5-lld)).
- **Late replies.** A reply for a step the process has already left is compensated if it holds a resource (a late `StockReserved` for a rejected order is released).
- **Deadlines.** Every waiting step has one. A recurring sweep resolves overdue processes ([ADR-003 amendment](ADR-003-job-scheduler-integration.md#amendment-2026-10-02-from-the-hld)).
- **Inspection.** A staff endpoint shows a process's state, its outstanding commands and its history.

## Trade-offs

- The orchestrator holds the most logic in the system, so it gets the most tests: every transition, every compensation, every late or duplicate reply.
- Compensation is semantic, not a rollback. A customer may see a charge followed by a refund (failure-handling S6 and S13).
- Some outcomes need people: a failed refund, a parcel that is lost or never booked.

## Consequences

- The order lifecycle is testable as a pure state machine fed with messages, without infrastructure.
- Extraction (V3) moves participants out of process without changing the saga: only the transport of commands and replies changes.
