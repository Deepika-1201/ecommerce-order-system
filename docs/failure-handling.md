# Failure handling

| | |
|---|---|
| Phase | 2 — Consistency and failure analysis |
| Status | Proposed, for architecture approval |
| Related | [Architecture §15](architecture.md#15-failure-handling) · [Order lifecycle](order-lifecycle.md) · [Consistency model](consistency-model.md) · [Event model](event-model.md) |

Scenarios S1–S12 are the brief's §10. S13–S20 are the ones the gateway and scheduler contracts, and the flash sale, add. Phase 11 automates every scenario, or documents why it stays manual.

Each scenario lists:

- **Detection:** how the system notices.
- **Recovery:** what it does.
- **Consistency:** what is temporarily out of step.
- **Compensation:** what is undone.
- **Duplicates:** what could run twice, and why that is harmless.
- **Customer:** what the customer sees.
- **Risk:** the residual risk to data integrity.

## S1. Stock reservation fails after the order is placed

- **Detection:** Inventory replies `StockReservationFailed` with the SKU and the units available.
- **Recovery:** the saga rejects the order (`OUT_OF_STOCK`).
- **Consistency:** none at risk. The reservation is all-or-nothing in one local transaction, so no partial hold exists.
- **Compensation:** none. Stock is reserved first, so nothing else was held.
- **Duplicates:** Inventory records the failure (`REJECTED`), so a redelivered `ReserveStock` returns the same answer instead of succeeding later. If a late `StockReserved` ever reaches a terminal order, the saga releases it.
- **Customer:** "Out of stock: *item*", and the cart is still there.
- **Risk:** none.

## S2. Payment fails after stock was reserved

- **Detection:** `payment.failed` (attempts exhausted) or `payment.expired` webhook, or the deadline poll.
- **Recovery:** the saga releases the coupon and the stock, then cancels the order (`PAYMENT_FAILED` or `PAYMENT_EXPIRED`).
- **Consistency:** the stock is available again within seconds of the webhook.
- **Compensation:** release coupon, release stock. Not on `payment.attempt_failed`: the customer can try again on the hosted page until the payment expires.
- **Is releasing always correct?** Only because the merchant account uses `AUTO_REFUND`. If the PSP later reports success, the gateway refunds the money instead of reporting a success (S13). Under `ACCEPT`, releasing could take money for stock already given away.
- **Duplicates:** release is keyed by order id; a released reservation ignores a second release.
- **Customer:** "Payment failed: order cancelled, nothing charged", plus an email.
- **Risk:** none.

## S3. The service crashes after the payment succeeded, before processing the outcome

- **Detection:** the gateway gets no `2xx`, because webhooks are acknowledged only after the inbox commit. It retries 10 times over about 47 hours. The deadline sweep also polls at hold expiry.
- **Recovery:** after restart, the redelivered webhook or the unprocessed inbox row confirms the order.
- **Consistency:** the order lags the payment by the gateway's retry interval (30 s for the first retry).
- **Compensation:** none.
- **Duplicates:** the inbox deduplicates on the gateway event id.
- **Customer:** "Payment processing" for longer, then "Confirmed".
- **Risk:** none. The hold outlives the gateway's ability to report success.

## S4. The payment-success event is delivered twice

- **Detection:** the inbox's unique gateway event id; `processed_messages` for internal messages.
- **Recovery:** the second delivery is acknowledged without effect. A webhook and a poll reporting the same success converge on the version check: the order is already `CONFIRMED`, so nothing changes.
- **Consistency:** unaffected.
- **Compensation:** none.
- **Duplicates:** `CommitReservation` is guarded by status; `CreateShipment` is unique per order, so no second shipment.
- **Customer:** nothing visible.
- **Risk:** none.

## S5. Inventory receives the reservation command twice

- **Detection:** the reservation row is unique per order id and is inserted before any counter changes.
- **Recovery:** the second command returns the recorded outcome (`HELD` with the same lines, or `REJECTED`). A concurrent duplicate waits on the unique index, then finds the first one's outcome.
- **Consistency:** counters change only in the transaction that inserts the reservation.
- **Compensation:** none.
- **Duplicates:** this scenario is the duplicate, and it is harmless.
- **Customer:** nothing visible.
- **Risk:** none.

## S6. The order is cancelled while its payment is being processed

- **Detection:** the gateway answers `CancelPayment` with `409 payment_invalid_state`.
- **Recovery:** the order stays `CANCELLING` until the payment ends. On success: refund, release, `CANCELLED`. On failure or expiry: release, `CANCELLED`.
- **Consistency:** `CANCELLING` can last until the gateway's grace for processing payments ends (30 minutes after expiry).
- **Compensation:** a refund if money was taken.
- **Duplicates:** cancel requests are idempotent (key and status guard); one refund per order and reason.
- **Customer:** "Cancelling", then "Cancelled", with "refund initiated" if they were charged.
- **Risk:** the customer may be charged and then refunded, visible on their statement. Accepted.

## S7. Two customers try to buy the last unit

- **Detection:** the loser's conditional update changes 0 rows.

  ```sql
  UPDATE inventory.stock_items
     SET reserved = reserved + :qty, version = version + 1
   WHERE sku = :sku AND location_id = :loc
     AND on_hand - reserved >= :qty;
  ```

  Under READ COMMITTED, the second transaction waits for the first one's row lock, then re-checks the `WHERE` clause against the committed row and finds no stock.
- **Recovery:** the loser tries to reclaim expired holds on that SKU once; if none exist, its order is rejected (`OUT_OF_STOCK`).
- **Consistency:** strong, because the row is the single gate.
- **Compensation:** none.
- **Duplicates:** none.
- **Customer:** one gets the item; the other gets "Out of stock".
- **Risk:** none. Overselling is impossible by construction.

## S8. The carrier API is unavailable

- **Detection:** the booking task fails (timeout or `5xx`).
- **Recovery:** the scheduler retries with exponential backoff for up to 24 hours. After that the task is dead-lettered, and an alert asks support to re-drive it, switch carrier, or cancel with a refund.
- **Consistency:** the order stays `CONFIRMED`: paid, with its stock committed.
- **Compensation:** none automatic, because the pivot has passed.
- **Duplicates:** booking carries the shipment id as the carrier reference; the simulator deduplicates on it, and a real carrier adapter looks the reference up before booking again.
- **Customer:** "Confirmed, preparing your shipment", with a delay email after 24 hours.
- **Risk:** a delayed delivery, not bad data.

## S9. Kafka is unavailable

- **Detection:** publish failures; `outbox_oldest_age_seconds` alert.
- **Recovery:** outbox rows wait, the relay retries with backoff, and the backlog drains in key order on recovery.
- **Consistency:** emails and availability hints are delayed. Checkout is unaffected in V1, because internal commands don't use Kafka until V2. From V2, orders pause between steps, and the deadline sweep re-drives them after recovery.
- **Compensation:** none.
- **Duplicates:** a relay crash after publishing but before marking can publish twice; consumers deduplicate on the message id.
- **Customer:** later emails.
- **Risk:** none: the outbox is the buffer and there are no dual writes. Outbox size is alerted before disk becomes a problem.

## S10. PostgreSQL is unavailable

- **Detection:** readiness checks fail; connection errors.
- **Recovery:** the RDS Multi-AZ failover to the synchronous standby happens within the RTO. In-flight transactions roll back and are retried: clients by `Idempotency-Key`, messages by redelivery, webhooks by the gateway, tasks by the scheduler.
- **Consistency:** no partial state, because every change and its outbox rows commit together.
- **Compensation:** none.
- **Duplicates:** absorbed by the idempotency layers (ADR-010).
- **Customer:** `503` for checkout and browsing (one database in V1).
- **Risk:** none with RPO 0.

## S11. A consumer crashes after its effect, before acknowledging

- **Detection:** Kafka redelivers from the last committed offset; the dispatcher re-delivers unmarked rows.
- **Recovery:** the `processed_messages` row is written in the same transaction as the effect, so the redelivery is a no-op.
- **Consistency:** unaffected.
- **Compensation:** none.
- **Duplicates:** this scenario is the duplicate, and it is harmless.
- **Customer:** nothing visible.
- **Risk:** none.

## S12. An old event arrives after a newer one

- **Scope of ordering:**
  - Kafka orders records within a partition only, never across keys or topics.
  - The relay preserves order per key.
  - Gateway webhooks and carrier tracking come with no ordering at all.
- **Detection:**
  - Our events carry a per-aggregate `sequence`.
  - Gateway resources carry a `version`.
  - Carrier events carry a timestamp.
- **Recovery:** stale updates are ignored. For example, a notification consumer that already handled `order.cancelled` (sequence 5) skips a late `order.confirmed` (sequence 4).
- **Consistency:** monotonic per aggregate.
- **Compensation:** none.
- **Duplicates:** the same checks absorb them.
- **Customer:** nothing incorrect; at worst a skipped, outdated email.
- **Risk:** none. No stale overwrite is possible.

## S13. A payment succeeds after its order was cancelled or its payment expired

- **Detection:** a `refund.succeeded` webhook initiated by the gateway's late-success policy; the payment itself stays `expired`, `failed` or `cancelled`.
- **Recovery:** the refund is recorded against the already-cancelled order.
- **Consistency:** stock is not touched.
- **Compensation:** the gateway performs it (`AUTO_REFUND`).
- **Duplicates:** inbox deduplication.
- **Customer:** charged and then refunded automatically, with an email explaining why.
- **Risk:** none for stock; some customer friction.

## S14. A gateway webhook never arrives

- **Detection:** the customer returning from the hosted checkout triggers a status poll; the deadline sweep polls at hold expiry.
- **Recovery:** the poll's result is applied through the same version check as a webhook.
- **Consistency:** confirmation delayed until the next poll.
- **Compensation:** none.
- **Duplicates:** if the webhook arrives after all, the version check ignores it.
- **Customer:** usually nothing, because of the poll on return.
- **Risk:** none.

## S15. The gateway is unavailable when the payment is created

- **Detection:** timeouts, `5xx`, an open circuit breaker.
- **Recovery:** retries with the same `Idempotency-Key` for up to 60 seconds. While the circuit is open, new placements fail fast with `503` before any stock is reserved.
- **Consistency:** stock is held for at most the retry budget.
- **Compensation:** after the budget, release the coupon and the stock; the order is rejected (`PAYMENTS_UNAVAILABLE`).
- **Duplicates:** a timeout with an unknown outcome is retried with the same key, so the gateway creates at most one payment. If the final attempt did create one, nobody holds its checkout URL, and it expires at the gateway without moving money; a best-effort `CancelPayment` is also sent.
- **Customer:** "Payments are temporarily unavailable. Please try again shortly."
- **Risk:** none.

## S16. The Job Scheduler is unavailable

- **Detection:** submission failures (outbox lag for the scheduler destination); the worker's gRPC session cannot be renewed.
- **Recovery:** the outbox keeps submissions. The worker fences itself (stops handlers, discards results) and re-registers with backoff. Tasks run after recovery.
- **Consistency:**
  - Delayed: deadline sweeps, booking retries and notification retries.
  - Unaffected: gateway-driven releases and confirmations.
  - Expired holds are still reclaimed on demand by competing reservations.
- **Compensation:** none.
- **Duplicates:** the scheduler is at-least-once; handlers are idempotent on the job id, and self-fencing prevents two workers acting on one job.
- **Customer:** later shipment bookings and emails.
- **Risk:** none for invariants.

## S17. Duplicate checkout requests (double submit)

- **Detection:** `Idempotency-Key` scoped to the principal, with a hash of the request; one order per quote (unique `quote_id`).
- **Recovery:**
  - Same key and body: the original `202` is replayed.
  - Same key, different body: `422`.
  - Same key while the first request is still running: `409` with `Retry-After`.
  - Different key, same quote: `409 quote_already_used`, with the existing order id.
- **Consistency:** unaffected.
- **Compensation:** none.
- **Duplicates:** this scenario is the duplicate, and it is harmless.
- **Customer:** one order.
- **Risk:** none.

## S18. A refund fails

- **Detection:** `refund.failed` webhook or poll.
- **Recovery:** an alert and a support task. Support retries the refund (a new attempt with a new key) or arranges a manual transfer, and the action is audited.
- **Consistency:** the order stays `CANCELLED` or `RETURNED_TO_ORIGIN`; the open refund is reported until resolved.
- **Compensation:** human.
- **Duplicates:** each refund attempt has its own key; at most one can succeed for the remaining refundable amount (the gateway enforces Σ refunds ≤ captured).
- **Customer:** "Refund delayed, we will contact you."
- **Risk:** money owed to a customer, tracked until resolved by the daily reconciliation.

## S19. Flash-sale overload

- **Detection:** waiting-room depth, admission rate, stock-row lock waits, `429` and `503` rates.
- **Recovery:**
  - Edge rate limits and bot checks.
  - The waiting room admits placements at the rate the stock row sustains.
  - One unit per customer.
  - Sold-out short-circuit.
  - A 5-minute payment window.
- **Consistency:** the database's conditional update remains the only gate.
- **Compensation:** none.
- **Duplicates:** each ticket admits one placement.
- **Customer:** "You're in line", then the checkout or "Sold out". Never a timeout.
- **Risk:** none for invariants. Fairness depends on ticket order, which phase 16 tests.

## S20. A worker crashes in the middle of a batch

- **Detection:** its lane lease expires and another worker takes the lane over.
- **Recovery:** rows not yet marked are delivered again.
- **Consistency:** the lane's work is delayed by one lease period.
- **Compensation:** none.
- **Duplicates:** handlers and consumers deduplicate on the message id.
- **Customer:** nothing visible.
- **Risk:** none.
