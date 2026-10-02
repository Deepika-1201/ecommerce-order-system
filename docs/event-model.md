# Event model

| | |
|---|---|
| Phase | 2 — High-level design |
| Status | Proposed, for architecture approval |
| Related | [Architecture §12](architecture.md#12-event-architecture) · [ADR-006 Kafka](decisions/ADR-006-kafka.md) · [ADR-008 Outbox](decisions/ADR-008-transactional-outbox.md) · [ADR-010 Idempotency](decisions/ADR-010-idempotency.md) · [ADR-011 Versioning](decisions/ADR-011-event-versioning.md) |

## 1. Kinds of message

| Kind | Meaning | Handlers | Travels on | Example |
|---|---|---|---|---|
| **Command** | A request to do something; may be refused | Exactly one: the owning context | Internal channel (in-process in V1, Kafka from V2) | `ReserveStock` |
| **Reply** | A command's outcome, addressed to the sender | The saga | Internal channel | `StockReserved`, `StockReservationFailed` |
| **Domain event** | A fact inside one context, used by its own logic or the saga | Inside the system | Internal channel | `PaymentSucceeded`, `ShipmentHandedOver` |
| **Integration event** | A published fact, part of this system's contract | Any number of subscribers | Kafka | `order.confirmed` |
| **Query** | A read with no side effects | The owning module | Synchronous call (in-process; HTTP after extraction) | Get quote, get order |
| **Task** | Work to run at a time, with retries | The Job Scheduler, then a handler here | Scheduler job API | Book a shipment; the deadline sweep |

Not every state change produces a message. Cart edits, quotes and profile changes publish nothing because nothing consumes them. An event is added when a consumer needs it, as in the scheduler project.

## 2. Envelope

Every message carries the same envelope. It adapts the event envelope from the scheduler's HLD §13.2, minus its tenant id (one tenant here), plus `source` and `causation_id`.

| Field | Type | Meaning |
|---|---|---|
| `message_id` | UUIDv7 | Unique id; every consumer deduplicates on it |
| `type` | string | For example `order.confirmed` or `inventory.reserve-stock` |
| `version` | int | Major version of the `data` schema ([ADR-011](decisions/ADR-011-event-versioning.md)) |
| `occurred_at` | timestamp, UTC, microseconds | When the state change committed |
| `source` | string | Producing module, for example `ecommerce/ordering` |
| `aggregate_type`, `aggregate_id` | string, UUID | The aggregate the message is about; the Kafka key |
| `sequence` | long | Per aggregate, +1 for each event; consumers ignore anything older than what they applied |
| `correlation_id` | string | The order id for order flows; the same across every message of one business flow |
| `causation_id` | UUID | The `message_id` that caused this message |
| `traceparent` | string | W3C trace context; also a Kafka header |
| `data` | object | The payload: ids and amounts, never names, addresses or contact details |

Kafka record: key = `aggregate_id`; value = the envelope as JSON; headers `traceparent`, `type` and `version`, so consumers can filter without parsing the value.

## 3. Internal commands and replies

| Command | Sent by | Handled by | Idempotent through | Replies |
|---|---|---|---|---|
| ReserveStock | Saga | Inventory | One reservation per order | StockReserved, StockReservationFailed |
| CommitReservation | Saga | Inventory | Status guard | ReservationCommitted, ReservationLost |
| ReleaseReservation | Saga | Inventory | Status guard | ReservationReleased |
| FulfillReservation | Saga | Inventory | Status guard | — |
| RestockReturn | Saga | Inventory | One restock per order | — |
| ReserveCoupon, CommitCoupon, ReleaseCoupon | Saga | Pricing | One redemption per order; status guard | CouponReserved, CouponUnavailable |
| CreatePayment | Saga | Payments | One payment record per order; gateway key from the order id | PaymentCreated, PaymentCreationFailed |
| CancelPayment | Saga | Payments | Gateway key from the order id | PaymentCancelled, PaymentCancelRefused |
| RefundPayment | Saga | Payments | Gateway key from the order id and the reason | RefundInitiated |
| CreateShipment | Saga | Fulfillment | One shipment per order | — (ShipmentBooked follows) |
| CancelShipment | Saga | Fulfillment | Status guard | ShipmentCancelled, ShipmentCancelRefused |

Domain events the saga consumes: PaymentSucceeded, PaymentFailed, PaymentExpired, PaymentCancelled, RefundSucceeded, RefundFailed (Payments); ShipmentBooked, ShipmentBookingFailed, ShipmentHandedOver, ShipmentDelivered, ShipmentReturnInitiated, ShipmentReturnedToOrigin (Fulfillment); ReservationExpired (Inventory).

## 4. Integration events (Kafka)

| Type | Topic | When | Data |
|---|---|---|---|
| `order.placed` | `ecom.ordering.order-events` | Order accepted | order id, order number, customer id or guest flag, totals, line count |
| `order.confirmed` | same | Payment succeeded and hold committed | order id, payment id, amount |
| `order.rejected` | same | Not accepted | order id, reason, SKU when out of stock |
| `order.cancelled` | same | Cancelled | order id, reason, refund amount (0 if none) |
| `order.shipped` | same | Handed over | order id, carrier, AWB |
| `order.delivered` | same | Delivered | order id, delivered at |
| `order.delivery_failed` | same | Return to origin started | order id |
| `order.returned_to_origin` | same | Back at the warehouse, refunded | order id, refund amount |
| `stock.level_changed` | `ecom.inventory.stock-events` | `available` changed | SKU, location, available, sequence |

## 5. Topics and consumers

| Topic | Key | Partitions | Retention | Consumer groups (V1) |
|---|---|---|---|---|
| `ecom.ordering.order-events` | order id | 12 | 7 days | `ecom-notifications` |
| `ecom.inventory.stock-events` | SKU | 12 | Compacted: the latest level per SKU | `ecom-catalog-availability` |
| `<topic>.<group>.dlt` | as source | 1 | 30 days | Operators: inspect and re-drive |

Replication factor 3 and `min.insync.replicas` 2 in the cloud; 1 locally. Producers use `acks=all` with idempotence enabled. From V2, the internal commands and replies get their own topics per context (for example `ecom.inventory.commands`), keyed by order id; designed in the phase 14 LLD.

## 6. Ordering

- **Guaranteed:** order per key within a topic. The relay publishes each key's rows in outbox order, and the producer's idempotence keeps that order through retries.
- **Not guaranteed:** order across keys, across topics, or between Kafka and the gateway's webhooks. Nothing relies on it.
- **Consumers** apply only messages with a newer `sequence` than the last one they applied for that aggregate, or rely on the state machine rejecting the transition.

## 7. Delivery, deduplication and retries

- Delivery is at-least-once everywhere. Nothing in this system claims exactly-once.
- Every handler writes a `processed_messages` row (consumer, `message_id`) in the same transaction as its effect. It commits the Kafka offset only after that transaction commits.
- `processed_messages` is kept for 30 days, longer than any topic's retention and any redelivery window.
- **Transient errors:** 3 retries in place (1 s, 2 s, 4 s), then the dead-letter topic.
- **Permanent errors** (deserialization or schema validation): straight to the dead-letter topic.
- A non-empty dead-letter topic alerts. Re-drive is an operator action after a fix.
- Consumers never call slow external systems inline. The notifications consumer records the notification and sends it with a 5-second timeout. A failed send stays pending for the retry sweep, so the partition is never blocked.

## 8. Versioning

Event types carry a major version. Within a version, changes are additive and optional, and consumers ignore unknown fields. A breaking change becomes a new version, published alongside the old one until every consumer has moved. Schemas live in the repository as JSON Schema and are compatibility-tested in CI ([ADR-011](decisions/ADR-011-event-versioning.md)).
