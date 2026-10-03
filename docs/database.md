# Database

| | |
|---|---|
| Status | Grows with each module's schema. Phase 2 (platform), phase 3 (catalog, customer), phase 4 (cart, pricing), phase 5 (inventory) |
| Decisions | [ADR-005](decisions/ADR-005-postgresql.md) (PostgreSQL, a schema per module), [ADR-008](decisions/ADR-008-transactional-outbox.md) (outbox), [ADR-009](decisions/ADR-009-inventory-reservation.md) (reservations), [ADR-010](decisions/ADR-010-idempotency.md) (idempotency), [ADR-021](decisions/ADR-021-stock-movements.md) (stock movements) |

## 1. Conventions

- **One schema per module,** owned by that module alone, with its own Flyway history (`db/migration/<module>/`). Foreign keys and joins never cross schemas.
- **Ids:** UUIDv7 (`Ids.newId()`), except identity columns where rows must sort in the order they were written: the outbox, the audit log and stock movements. `bigint` for counters and versions.
- **Money:** `bigint` paise. **Time:** `timestamptz`, written with microsecond precision.
- **JSON:** `jsonb` for structured data that is queried or validated, `text` for payloads that must stay byte-exact, such as message envelopes.
- **Status columns** are `text` with a `CHECK` listing the allowed values.
- **Changes during a deployment** are expand/contract: add, backfill, switch, then drop in a later release.
- **Personal data** (names, emails, phones, addresses) lives only in the `customer` schema.

## 2. `platform` (phase 2)

| Table | Purpose | Key columns | Retention |
|---|---|---|---|
| `outbox` | One row per message and destination ([LLD §2.2](low-level-design.md#22-publishing)) | `id` identity; `message_id` + `destination` unique; `aggregate_type`, `aggregate_id`, `sequence` order delivery; `delivered_at` / `parked_at` | Delivered rows: 7 days |
| `processed_messages` | Which consumer handled which message | Primary key `consumer` + `message_id` | 30 days |
| `scheduled_tasks` | The in-process task scheduler ([LLD §2.7](low-level-design.md#27-tasks)) | `dedupe_key` unique while pending or running; `status`, `run_at`, `lease_until`, `attempts` | Finished tasks: 30 days |
| `idempotency_keys` | API idempotency ([LLD §2.8](low-level-design.md#28-api-idempotency-keys)) | Primary key `scope` + `key`; `fingerprint`; stored response | Until `expires_at` (24 h) |
| `audit_log` | Append-only record of staff and money actions | Actor, action, target, `details` (`jsonb`); a trigger rejects `UPDATE` and `DELETE` | Kept; monthly partitions later |

Indexes worth knowing:

| Index | Serves |
|---|---|
| `outbox_pending (next_attempt_at, id)` partial on pending rows | Claiming due rows |
| `outbox_pending_by_aggregate (destination, aggregate_type, aggregate_id, sequence, id)` partial on undelivered rows | The eligibility rule |
| `scheduled_tasks_active_dedupe (dedupe_key)` unique, partial on pending and running | Task deduplication |

## 3. `catalog` (phase 3)

```mermaid
erDiagram
    categories ||--o{ categories : "parent_id"
    categories ||--o{ products : "category_id"
    products ||--o{ variants : "product_id"
    products ||--o{ product_images : "product_id"
```

| Table | Columns | Constraints and indexes |
|---|---|---|
| `categories` | `id`, `parent_id`, `name`, `slug`, `created_at`, `updated_at` | `slug` unique; `parent_id` references `categories` |
| `products` | `id`, `category_id`, `title`, `description`, `gst_category`, `options` (`jsonb`: `[{name, values[]}]`), `status`, `version`, `search_vector`, `created_at`, `updated_at` | `search_vector` generated from title (weight A) and description (weight B), GIN index; `(category_id, id)` partial on `ACTIVE`; `(status, id)` |
| `variants` | `id`, `product_id`, `sku`, `option_values` (`jsonb`: `{name: value}`), `combination_key`, `price_paise`, `status`, `created_at`, `updated_at` | `sku` unique; `(product_id, combination_key)` unique; `price_paise` between 1 and 1,000,000,000 |
| `product_images` | `id`, `product_id`, `object_key`, `content_type`, `size_bytes`, `alt_text`, `status`, `position`, `created_at`, `completed_at` | `object_key` unique; `(product_id, position)`; `created_at` partial on `PENDING`, for expiry |

- `combination_key` is the canonical form of a variant's option values (`color=red;size=m`), so the unique index enforces one variant per combination.
- `version` increases with every change to the product, its variants or its images. It is the admin `ETag`.

## 4. `customer` (phase 3)

```mermaid
erDiagram
    customers ||--o{ addresses : "customer_id"
```

| Table | Columns | Constraints and indexes |
|---|---|---|
| `customers` | `id`, `subject` (the identity provider's user id), `email`, `name`, `phone`, `created_at`, `updated_at` | `subject` unique |
| `addresses` | `id`, `customer_id`, `recipient_name`, `phone`, `line1`, `line2`, `landmark`, `city`, `state_code` (GST state code), `pin_code`, `is_default`, `created_at`, `updated_at` | `(customer_id)` unique where `is_default`; `pin_code` checked (`^[1-9][0-9]{5}$`); `(customer_id, created_at)` |

Account deletion (phase 13) anonymizes these rows instead of deleting them where orders still refer to the customer.

## 5. `cart` (phase 4)

```mermaid
erDiagram
    carts ||--o{ cart_lines : "cart_id"
```

| Table | Columns | Constraints and indexes |
|---|---|---|
| `carts` | `id`, `customer_id`, `guest_token_hash` (SHA-256 of the cart token), `coupon_code`, `version`, `created_at`, `updated_at`, `expires_at` | Exactly one of `customer_id` and `guest_token_hash`; each unique; `expires_at`, for the expiry task |
| `cart_lines` | `cart_id`, `sku`, `quantity`, `added_price_paise`, `added_at`, `updated_at` | Primary key `(cart_id, sku)`; `quantity` between 1 and 10; deleted with their cart |

Merged and expired carts are deleted, not kept with a status ([LLD §4.3](low-level-design.md#43-carts)).

## 6. `pricing` (phase 4)

```mermaid
erDiagram
    coupons ||--o{ coupon_redemptions : "coupon_id"
    quotes ||--|{ quote_lines : "quote_id"
```

| Table | Columns | Constraints and indexes |
|---|---|---|
| `coupons` | `id`, `code`, `kind`, `percent_bps`, `max_discount_paise`, `amount_paise`, `min_order_paise`, `valid_from`, `valid_until`, `total_limit`, `per_customer_limit`, `active`, `reserved`, `redeemed`, `version`, `created_at`, `updated_at` | `code` unique; the rule fields match `kind`; `reserved` and `redeemed` at least 0; `reserved + redeemed ≤ total_limit` |
| `coupon_redemptions` | `id`, `coupon_id`, `order_id`, `customer_id`, `status`, `created_at`, `updated_at` | `order_id` unique; `(coupon_id, customer_id)` partial on `HELD` and `COMMITTED`, for the per-customer limit |
| `quotes` | `id`, `cart_id`, `customer_id`, `coupon_id`, `coupon_code`, `supply_state_code`, `delivery_state_code`, shipping fee with its taxable value, rate and tax, totals, `valid_until`, `created_at` | Read by id, with the owner columns in the condition; `valid_until`, for the purge task |
| `quote_lines` | `quote_id`, `line_no`, `sku`, `product_id`, `variant_id`, `title`, `option_values` (`jsonb`), `image_key`, `quantity`, `unit_price_paise`, `previous_unit_price_paise`, `gross_paise`, `discount_paise`, `amount_paise`, `taxable_value_paise`, `gst_rate_bps`, `cgst_paise`, `sgst_paise`, `igst_paise` | Primary key `(quote_id, line_no)`; `taxable_value_paise + cgst_paise + sgst_paise + igst_paise = amount_paise` |

- Quotes are immutable, and deleted a day after they expire; orders keep their own copy (phase 6).
- The coupon counters are changed only by conditional updates ([LLD §4.10](low-level-design.md#410-coupon-redemptions)).

## 7. `inventory` (phase 5)

```mermaid
erDiagram
    locations ||--o{ stock_items : "location_code"
    stock_items ||--o{ stock_movements : "sku, location_code"
    reservations ||--|{ reservation_lines : "reservation_id"
```

| Table | Columns | Constraints and indexes |
|---|---|---|
| `locations` | `code`, `name`, `created_at` | Primary key `code`. One row in V1: `BLR1` |
| `stock_items` | `sku`, `location_code`, `on_hand`, `reserved`, `version`, `created_at`, `updated_at` | Primary key `(sku, location_code)`; `0 ≤ reserved ≤ on_hand` |
| `reservations` | `id`, `order_id`, `status`, `expires_at`, `short_sku`, `short_available`, `version`, `created_at`, `updated_at` | `order_id` unique; `expires_at` required while `HELD`; `short_sku` and `short_available` exactly when `REJECTED`; `(expires_at)` partial on `HELD`, for expiry |
| `reservation_lines` | `reservation_id`, `sku`, `location_code`, `quantity` | Primary key `(reservation_id, sku, location_code)`; `quantity` at least 1 |
| `stock_movements` | `id` (identity), `sku`, `location_code`, `kind`, `quantity`, `reason`, `note`, `reference`, `order_id`, `on_hand_after`, `actor_id`, `created_at` | References `stock_items`; the sign of `quantity` matches `kind` and `reason`; `reason` exactly for adjustments, `order_id` exactly for handovers and returns; `(order_id, kind, sku, location_code)` unique; `(sku, location_code, id)`, for a SKU's history; a trigger refuses `UPDATE` and `DELETE` |

- The counters change only by conditional updates ([LLD §5.4](low-level-design.md#54-reserving)).
- Movements are never updated or deleted, and a stock item's `on_hand` equals the sum of its movements ([ADR-021](decisions/ADR-021-stock-movements.md)). Their ids are taken under the stock row's lock, so a SKU's movements sort in the order they were applied ([LLD §5.8](low-level-design.md#58-stock-movements)).
- Reservation lines have no foreign key to `stock_items`: a rejected reservation can name a SKU that has no stock item.
- Reservations are kept, like the orders they belong to.
