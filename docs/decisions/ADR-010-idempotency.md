# ADR-010: Layered idempotency

- **Status:** Accepted (2026-10-02), extended in phase 5 (see the end)
- **Date:** 2026-10-02
- **Related:** [ADR-002](ADR-002-payment-gateway-integration.md), [ADR-003](ADR-003-job-scheduler-integration.md), [ADR-008](ADR-008-transactional-outbox.md), [failure handling](../failure-handling.md)

## Context

Everything here can arrive twice:

- client retries and double submits;
- gateway webhooks (at-least-once);
- Kafka redeliveries;
- outbox resends;
- scheduler re-executions.

Money operations must be idempotent (brief, rule 7). The gateway and the scheduler both offer idempotency keys with known retention: 7 days and 24 hours.

## Problem

Where is duplicate processing prevented, so that no duplicate can create a second order, charge, reservation, shipment, refund or email?

## Options considered

| Option | Covers | Gaps |
|---|---|---|
| API idempotency keys only | Client retries | Webhooks, redeliveries, outbound retries |
| Consumer deduplication only | Redeliveries | Concurrent client duplicates; outbound calls with unknown outcome |
| **Layered**: API keys, domain uniqueness, message deduplication, outbound keys, guarded transitions | Every source of duplicates | More mechanisms to keep consistent |

## Decision

**Layered idempotency** (proposed). Each layer catches what the previous ones cannot.

1. **API.** `Idempotency-Key` is required on `POST /v1/orders`, cancellations and staff money actions. Keys are scoped to the principal and stored with a request hash, the response and a lease, for 24 hours.
   - Same key and body: the response is replayed.
   - Same key, different body: `422`.
   - First request still running: `409` with `Retry-After`.
   This mirrors the gateway's design.
2. **Domain uniqueness.** Unique constraints for one order per quote, one reservation, one coupon redemption, one payment record and one shipment per order, and one refund per order and reason.
3. **Messages.** A `processed_messages` row (consumer, `message_id`) is written in the same transaction as the effect, for every internal and Kafka handler. Gateway webhooks go through an inbox keyed by the gateway's event id, with the raw body stored as text.
4. **Outbound calls.**
   - Gateway keys are derived from the business operation: `ecom:order:{id}:payment`, `ecom:order:{id}:checkout-session`, `ecom:order:{id}:cancel`, `ecom:order:{id}:refund:{reason}`. A retry after a timeout with an unknown outcome reuses the key, so it creates nothing new.
   - Scheduler submissions use the outbox row's `message_id` as `Idempotency-Key`, plus a business `dedupe_key`.
5. **Guarded transitions.** A transition that has already happened is a no-op, not an error: committing a committed reservation, confirming a confirmed order.

## Trade-offs

- Five mechanisms to keep consistent; the failure suite (phase 11) tests each source of duplicates.
- Key retention bounds retries: every retry budget here (minutes to hours) ends well inside the gateway's 7 days and the scheduler's 24 hours.

## Consequences

- No handler may have a side effect outside its transaction except through the outbox or an outbound call with a derived key.
- The `processed_messages` and inbox tables are kept 30 days, longer than any redelivery window.

## Extension (2026-10-03, phase 5)

`Idempotency-Key` is also required on the warehouse's stock receipts and adjustments (`POST /v1/warehouse/stock/{sku}/receipts` and `…/adjustments`). A retried receipt would otherwise add its units twice, and the phantom units could be sold. The keys are scoped to the operator, like every other key ([LLD §5.9](../low-level-design.md#59-warehouse-api)).
