# API

| | |
|---|---|
| Status | Grows with each phase. Phase 3: identity, customers, catalog. Phase 4: carts, quotes, coupons. Phase 5: warehouse stock. Phase 6: orders |
| Contract | `docs/api/openapi.json`, generated from the code and checked in CI ([ADR-016](decisions/ADR-016-openapi-from-code.md)) |
| Design | [LLD §3](low-level-design.md#3-catalog-customers-and-identity-phase-3), [LLD §4](low-level-design.md#4-cart-pricing-and-coupons-phase-4), [LLD §5](low-level-design.md#5-inventory-phase-5), [LLD §6](low-level-design.md#6-ordering-and-the-saga-phase-6) |

## 1. Conventions

| Topic | Rule |
|---|---|
| Base path | `/v1`. Within a version, changes are additive only; removing or renaming a field, or adding a required one, needs `/v2` |
| Format | JSON, `snake_case` fields; fields without a value are omitted. Clients ignore fields they do not know |
| Ids | UUIDv7 strings. They are unguessable, but authorization never relies on that |
| Money | Integer paise, with `currency: "INR"`. List prices include GST ([ADR-018](decisions/ADR-018-gst-inclusive-prices.md)); GST rates are basis points (`1800` is 18%) |
| Time | ISO-8601 in UTC, with up to microsecond precision |
| Request ids | `X-Request-Id` is echoed or generated, and appears in every error |

## 2. Authentication and roles

- **Tokens:** `Authorization: Bearer <access token>`, issued by the identity provider (Keycloak). The service checks the signature, issuer, audience (`ecommerce-api`) and expiry.
- **Roles:** `customer`, `support`, `warehouse`, `admin`. Staff roles do not include `customer`.
- **Object-level access:** customers reach their own resources through `/v1/me` only, and another customer's resource is `404` ([ADR-017](decisions/ADR-017-customer-resources-under-me.md)).
- **Guest carts:** `POST /v1/guest/cart` returns a cart token once. Every other `/v1/guest/cart` call sends it in the `Cart-Token` header; an unknown token is `404` ([ADR-020](decisions/ADR-020-guest-cart-tokens.md)). Keep it secret: it is the only credential for that cart.
- **Anonymous access:** catalog reads, reference data and guest carts need no access token.

## 3. Errors

Every error is `application/problem+json` (RFC 9457), with `type`, `title`, `status` and `detail`, plus:

- `code`: stable and machine-readable;
- `request_id`;
- for validation errors, `errors`: a list of `{field, message}`, with fields in `snake_case`.

| Code | Status | Meaning |
|---|---|---|
| `validation_failed` | 400 | The body or parameters break a rule; see `errors` |
| `malformed_request` | 400 | The body is not valid JSON |
| `invalid_request` | 400 | Another client error, such as a missing parameter |
| `invalid_cursor` | 400 | The pagination cursor was not issued by this API |
| `invalid_options`, `invalid_option_values` | 400 | A product's options, or a variant's option values, break the option rules |
| `invalid_coupon_rule` | 400 | A coupon's fields do not match its kind, or its window ends before it starts |
| `invalid_adjustment` | 400 | A stock adjustment's sign does not match its reason |
| `idempotency_key_required`, `invalid_idempotency_key` | 400 | `Idempotency-Key` is missing, or not 1–255 printable characters |
| `unauthorized` | 401 | No token, or the token is invalid or expired |
| `forbidden` | 403 | The token lacks the role |
| `not_found` | 404 | No such resource, or not the caller's |
| `method_not_allowed`, `not_acceptable`, `unsupported_media_type` | 405, 406, 415 | HTTP-level mismatches |
| `idempotency_request_in_progress` | 409 | Another request with this key is running; see `Retry-After` |
| `address_limit_reached` | 409 | A customer already has 10 addresses |
| `category_slug_taken`, `category_cycle`, `category_too_deep` | 409 | Category rules |
| `sku_taken`, `variant_exists`, `too_many_variants`, `options_locked` | 409 | Variant and option rules |
| `product_needs_active_variant` | 409 | An active product needs an active variant |
| `image_limit_reached`, `upload_not_found` | 409 | Image rules |
| `cart_full`, `cart_empty`, `cart_has_unavailable_items` | 409 | A cart already has 50 lines (also when merging); a quote of an empty cart; a quote of a cart with lines that can no longer be bought |
| `coupon_exhausted`, `coupon_already_used` | 409 | The coupon's total limit, or the customer's own limit, is used up |
| `coupon_code_taken`, `limit_below_usage` | 409 | Coupon administration: the code exists; a limit below current usage |
| `adjustment_below_reserved` | 409 | A stock adjustment would leave fewer units on hand than orders have reserved |
| `quote_expired`, `quote_already_ordered` | 409 | Placing an order: the quote's 10 minutes are over, or another order was placed from it |
| `order_invalid_state` | 409 | The order cannot be cancelled in its status |
| `precondition_failed` | 412 | `If-Match` does not match the current version |
| `idempotency_key_reused`, `upload_mismatch` | 422 | The key was used for a different request; the uploaded object is not what was announced |
| `unknown_category` | 422 | The category or parent category in the body does not exist |
| `item_unavailable` | 422 | The SKU does not exist or cannot be bought now |
| `address_not_found`, `address_state_mismatch` | 422 | Placing an order: no such address among the caller's, or the delivery address is in another state than the quote's |
| `coupon_not_found`, `coupon_not_yet_valid`, `coupon_expired`, `coupon_requires_sign_in`, `coupon_minimum_not_met` | 422 | The coupon cannot apply to this cart; see [LLD §4.9](low-level-design.md#49-coupons) |
| `precondition_required` | 428 | `If-Match` is required |
| `internal_error` | 500 | Unexpected; details are in the logs under the request id |
| `stock_busy` | 503 | A stock item stayed locked longer than the lock timeout; see `Retry-After` |

## 4. Lists

- `limit`: 1–50, default 20.
- `cursor`: opaque, taken from the previous page's `next_cursor`.
- Responses: `{"items": [...], "next_cursor": "..."}`. `next_cursor` is absent on the last page.

## 5. Caching and concurrency

- **Public reads** carry `Cache-Control: public, max-age=30` (a day for reference data such as `/v1/states`) and an `ETag`; `If-None-Match` gets `304 Not Modified`. Everything else is `Cache-Control: no-store`.
- **Concurrent edits:** a product's `ETag` is its version. `PATCH` needs `If-Match`: a stale version gets `412`, and a missing header `428`.
- **Carts:** each write sets one line's quantity, so retries and concurrent tabs are safe without `If-Match`. A cart's `ETag` is its version; sending it in `If-Match` makes a write conditional (`412` when stale).
- **Retries:** `Idempotency-Key` is required where a retry must not repeat an effect: stock receipts and adjustments (phase 5); order placement, cancellations and staff money actions (phase 6) ([ADR-010](decisions/ADR-010-idempotency.md)).

## 6. Endpoints

| Endpoint | Access | Phase |
|---|---|---|
| `GET /v1/categories` | Anyone | 3 |
| `GET /v1/products`, `GET /v1/products/{id}` | Anyone | 3 |
| `GET /v1/states` | Anyone | 3 |
| `GET`, `PATCH /v1/me` | `customer` | 3 |
| `GET`, `POST /v1/me/addresses`; `GET`, `PUT`, `DELETE /v1/me/addresses/{id}`; `POST /v1/me/addresses/{id}/default` | `customer` | 3 |
| `POST /v1/admin/catalog/categories`, `PATCH /v1/admin/catalog/categories/{id}`, `POST …/categories/{id}/move` | `admin` | 3 |
| `GET`, `POST /v1/admin/catalog/products`; `GET`, `PATCH /v1/admin/catalog/products/{id}` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/activate`, `/archive` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/variants`, `PATCH …/variants/{variantId}` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/images`, `POST …/images/{imageId}/complete`, `DELETE …/images/{imageId}` | `admin` | 3 |
| `GET /v1/me/cart`; `PUT`, `DELETE /v1/me/cart/lines/{sku}`; `PUT`, `DELETE /v1/me/cart/coupon` | `customer` | 4 |
| `POST /v1/me/cart/merge` (with `Cart-Token`); `POST /v1/me/cart/quotes`; `GET /v1/me/cart/quotes/{id}` | `customer` | 4 |
| `POST /v1/guest/cart`; the cart, line, coupon and quote endpoints above under `/v1/guest/cart`, with `Cart-Token` | Anyone | 4 |
| `GET`, `POST /v1/admin/pricing/coupons`; `GET`, `PATCH /v1/admin/pricing/coupons/{id}` | `admin` | 4 |
| `GET /v1/warehouse/stock`; `GET /v1/warehouse/stock/{sku}`; `GET /v1/warehouse/stock/{sku}/movements` | `warehouse` | 5 |
| `POST /v1/warehouse/stock/{sku}/receipts`, `POST /v1/warehouse/stock/{sku}/adjustments` (with `Idempotency-Key`) | `warehouse` | 5 |
| `GET`, `POST /v1/me/orders` (with `Idempotency-Key`); `GET /v1/me/orders/{id}`; `POST /v1/me/orders/{id}/cancel` (with `Idempotency-Key`) | `customer` | 6 |
| `GET /v1/support/orders/{id}`; `POST /v1/support/orders/{id}/cancel` (with `Idempotency-Key`) | `support` | 6 |

The OpenAPI document has the request and response schemas. Locally it is also served at `http://localhost:8081/actuator/openapi`.
