# Consistency model

| | |
|---|---|
| Phase | 2 — Consistency and failure analysis |
| Status | Proposed, for architecture approval |
| Related | [Architecture §14](architecture.md#14-consistency-model) · [Failure handling](failure-handling.md) · [ADR-009](decisions/ADR-009-inventory-reservation.md) |

## 1. Rules

1. **Strong** means linearizable per row or aggregate: one PostgreSQL primary, one transaction per change, optimistic locks or conditional updates. It is never provided by a cache or a replica.
2. Strong consistency is reserved for what can lose money or stock: stock and coupon counters, and each aggregate's own state.
3. Everything that crosses a context boundary is **eventual**, with a named source of truth, a staleness target and a metric that measures it.
4. Read replicas (later) serve only catalog browsing and reports. Never checkout, stock, or an order the reader has just changed (read-your-writes).
5. A stale read may make the customer experience worse (an "in stock" hint for a sold-out item), never make the data wrong (an oversold item).

## 2. Matrix

| Data | Source of truth | Written by | Consistency | Staleness target | Notes |
|---|---|---|---|---|---|
| Stock, for the reservation decision | `inventory.stock_items` | Inventory | Strong (conditional update) | 0 | The only gate against overselling |
| Availability hint on product pages | Projection in Catalog | Catalog's consumer of `stock.level_changed` | Eventual | ≤ 5 s | May say "in stock" for a sold-out SKU; checkout decides |
| Product data and list prices | `catalog` | Admins | Strong within Catalog | 0 | Admin reads see their own writes |
| Search results | Full-text columns in `catalog` | Catalog, same transaction | Strong in V1 | 0 | Becomes eventual if search moves to a separate engine |
| Price of an order | Quote, copied into the order | Pricing, then Ordering | Fixed at placement | — | A quote holds its prices until `valid_until` (10 min), even if list prices change |
| Coupon usage | `pricing.coupons` | Pricing | Strong (conditional update) | 0 | Same pattern as stock |
| Cart | `cart.carts` | Cart | Strong per cart (optimistic lock) | 0 | Concurrent edits from two tabs: the second gets `409` and re-reads |
| Order status | `ordering.orders` | Order saga | Strong per order; eventual relative to payment and shipment facts | Seconds normally; at most the deadline | A customer can see `AWAITING_PAYMENT` for a few seconds after paying |
| Payment status | The gateway; mirrored in `payments.payment_records` | Payments, from webhooks and polls | Eventual, monotonic by gateway `version` | Webhook latency; at most the hold expiry via polling | The gateway is authoritative for money |
| Refund status | The gateway; mirrored in `payments.refunds` | Payments | Eventual | Webhook latency; daily reconciliation | Open refunds are reported until terminal |
| Shipment status | The carrier; mirrored in `fulfillment.shipments` | Fulfillment | Eventual, monotonic by carrier timestamp | Carrier latency | Never moves backwards |
| Notifications | `notifications.notifications` | Notifications | Eventual; at most once per order and kind | Minutes | A late notification is skipped if a later event superseded it |
| Customer profile and addresses | `customer` | Customer | Strong | 0 | The order keeps its own snapshot |
| Order history | `ordering.orders` | Ordering | Strong in V1 (primary) | 0 | Moving it to a replica requires read-your-writes handling |
| Reconciliation and reports | Derived | Recurring jobs | Eventual | Hours | Daily |

## 3. Where customers can observe eventual consistency

| Situation | What the customer sees | Bound |
|---|---|---|
| Paid, webhook in flight | "Payment processing" | Seconds; at most the hold expiry |
| Sold out, hint not yet updated | "In stock", then "out of stock" at checkout | ≤ 5 s |
| Cancelled while the payment was in flight | "Cancelling", then "Cancelled, refund initiated" | The gateway's grace for processing payments |
| Shipped, tracking webhook late or out of order | Earlier tracking status | Carrier latency |
| Notification after a state change | Email arrives after the page shows the change | Minutes |

## 4. Caching

None in V1 ([ADR-012](decisions/ADR-012-no-cache-v1.md)). The only exception is the per-instance flash-sale sold-out flag ([ADR-009](decisions/ADR-009-inventory-reservation.md)). It can only reject early; it cannot accept an order the database would refuse, so a stale flag costs a sale for at most its 1-second lifetime and never an oversell.
