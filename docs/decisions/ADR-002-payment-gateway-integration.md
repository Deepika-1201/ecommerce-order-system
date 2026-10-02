# ADR-002: Payments through the ecosystem's Payment Gateway, as a merchant

- **Status:** Proposed
- **Date:** 2026-10-02
- **Related:** [ADR-001](ADR-001-ecosystem-boundaries.md), [ADR-003](ADR-003-job-scheduler-integration.md), [requirements §5.7](../requirements.md#57-payments-through-the-payment-gateway)

## Context

The ecosystem diagram has e-commerce calling the Payment Gateway. The gateway's merchant contract (its `docs/openapi.yaml` and low-level design) provides:

| Capability | Contract |
|---|---|
| Create, confirm, capture, cancel, refund | REST. Every `POST` needs an `Idempotency-Key`, scoped to the merchant and kept 7 days: the same key with a different body → `422`; still in progress → `409` with `Retry-After` |
| Payments per order | `merchant_order_id` is indexed, not unique; an order can have several payments |
| Hosted checkout | `POST /v1/checkout-sessions` returns a bearer URL for the customer: UPI, cards, netbanking |
| Outcomes | Webhooks such as `payment.succeeded`, `payment.failed`, `payment.expired`, `payment.cancelled` and `refund.*`, signed with `PG-Signature: t=…,v1=HMAC-SHA256(secret, t + "." + body)`: 5-minute tolerance, several `v1` values during secret rotation, `PG-Event-Id` for deduplication. At-least-once and unordered (use the resource `version`); 10 attempts over about 47 hours |
| Status | `GET /v1/payments/{id}` and `GET /v1/refunds/{id}`; no listing by order id or time window |
| Expiry | `expires_in_seconds`, default 15 min. A payment still processing at expiry gets a 30-minute grace |
| Cancel | Allowed before an attempt is in flight and when authorized; refused while processing (`payment_invalid_state`) |
| Late success | Per-merchant `late_success_policy`: `AUTO_REFUND` (default) or `ACCEPT` |
| Limits | INR only; per-merchant rate limits of 100 writes/s and 200 reads/s per instance, answered with `429` and `Retry-After` |

## Problem

How does this system request payments and learn their outcomes reliably, given timeouts, duplicate and unordered webhooks, and payments that succeed late?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Merchant API, signed webhooks and a polling backstop** | How real merchants integrate with real gateways; uses a tested contract; isolation between merchants holds | Webhooks can be late; needs an inbox and a polling backstop |
| B. Consume the gateway's internal events from the shared broker | One integration style for everything | Bypasses merchant isolation (one topic holding every merchant's payments); couples this system to the gateway's internal schema; the gateway has no broker (its ADR-004) |
| C. Payment orchestration inside this system, with its own PSP adapters | No cross-system dependency | Duplicates the gateway and removes the cross-system boundary the ecosystem exists to show |
| D. Polling only | Simple | Slow outcomes and needless load on the gateway |

## Decision

**Option A** (proposed).

- **Port and adapters.** A `PaymentGateway` port, with an HTTP adapter for the real gateway and an in-process fake with scripted outcomes for standalone runs and tests.
- **One payment per order in V1.** `merchant_order_id` is the order id. The `Idempotency-Key` is derived from the order id, so a retried create returns the same payment, even after a timeout with an unknown outcome. Capture is automatic; UPI debits the customer immediately anyway.
- **Created after the reservation.** A payment is created only once its stock is reserved, so customers are never charged for stock that doesn't exist. Payment creations are then bounded by the stock on sale rather than by checkout attempts, which keeps a flash sale within the gateway's rate limits.
- **Expiry and reservation.** The payment's expiry is aligned with the reservation. Because a processing payment can still succeed during the gateway's 30-minute grace, the saga either keeps the reservation until the payment is terminal or refunds a success that arrives after the stock was released. The choice is made in saga design.
- **Webhook intake.** Verify the signature and timestamp against the raw body, record the event in an inbox keyed by `PG-Event-Id`, and answer `2xx` only after that commit. Apply the event to the payment record only if its `version` is newer.
- **Polling backstop.** A scheduler task (ADR-003) polls `GET /v1/payments/{id}` when a payment has no terminal outcome by its deadline.
- **Cancellation during payment.** If the gateway refuses to cancel because an attempt is in flight, the order records that cancellation was requested and completes it when the payment resolves, refunding if it succeeded.
- **Late success.** The merchant account uses `AUTO_REFUND`, and the order records the system refund it learns about through `refund.succeeded`.
- **Credentials.** The merchant API key and webhook secret come from the secrets manager and are never logged. The webhook route is exempt from end-user authentication at the edge; its signature authenticates it.

## Trade-offs

- Checkout depends on the gateway: without it, there are no new prepaid orders. The outbox and idempotency keys make that dependency safe, not optional.
- Outcomes are asynchronous. A customer may see "payment processing" while webhooks are retried; the polling backstop bounds the wait.
- The market is coupled to the gateway: INR only until the gateway supports more.
- Reconciliation can only check payments this system knows about. That is sufficient because creation is idempotent and retried until it succeeds; a merchant listing API in the gateway would make it complete.

## Consequences

- Contract tests run against the gateway's OpenAPI document. A breaking change to the gateway's merchant API needs a new API version there.
- Local runs use either the fake or the real gateway, started by this repository's optional compose profile.
- Ride-hailing can integrate the same way, which gives the gateway a second real merchant.
