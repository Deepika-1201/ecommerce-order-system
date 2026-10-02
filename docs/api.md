# API

| | |
|---|---|
| Status | Grows with each phase. Phase 3: identity, customers, catalog |
| Contract | `docs/api/openapi.json`, generated from the code and checked in CI ([ADR-016](decisions/ADR-016-openapi-from-code.md)) |
| Design | [LLD §3](low-level-design.md#3-catalog-customers-and-identity-phase-3) |

## 1. Conventions

| Topic | Rule |
|---|---|
| Base path | `/v1`. Within a version, changes are additive only; removing or renaming a field, or adding a required one, needs `/v2` |
| Format | JSON, `snake_case` fields; fields without a value are omitted. Clients ignore fields they do not know |
| Ids | UUIDv7 strings. They are unguessable, but authorization never relies on that |
| Money | Integer paise, with `currency: "INR"` |
| Time | ISO-8601 in UTC, with up to microsecond precision |
| Request ids | `X-Request-Id` is echoed or generated, and appears in every error |

## 2. Authentication and roles

- **Tokens:** `Authorization: Bearer <access token>`, issued by the identity provider (Keycloak). The service checks the signature, issuer, audience (`ecommerce-api`) and expiry.
- **Roles:** `customer`, `support`, `warehouse`, `admin`. Staff roles do not include `customer`.
- **Object-level access:** customers reach their own resources through `/v1/me` only, and another customer's resource is `404` ([ADR-017](decisions/ADR-017-customer-resources-under-me.md)).
- **Anonymous access:** catalog reads and reference data need no token.

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
| `idempotency_key_required`, `invalid_idempotency_key` | 400 | `Idempotency-Key` is missing, or not 1–255 printable characters |
| `unauthorized` | 401 | No token, or the token is invalid or expired |
| `forbidden` | 403 | The token lacks the role |
| `not_found` | 404 | No such resource, or not the caller's |
| `method_not_allowed`, `not_acceptable`, `unsupported_media_type` | 405, 406, 415 | HTTP-level mismatches |
| `idempotency_request_in_progress` | 409 | Another request with this key is running; see `Retry-After` |
| `address_limit_reached` | 409 | A customer already has 10 addresses |
| `category_slug_taken`, `category_cycle`, `category_too_deep` | 409 | Category rules |
| `sku_taken`, `variant_exists`, `too_many_variants`, `options_locked` | 409 | Variant and option rules |
| `product_needs_active_variant`, `invalid_status_transition` | 409 | Product status rules |
| `image_limit_reached`, `upload_not_found` | 409 | Image rules |
| `precondition_failed` | 412 | `If-Match` does not match the current version |
| `idempotency_key_reused`, `upload_mismatch` | 422 | The key was used for a different request; the uploaded object is not what was announced |
| `precondition_required` | 428 | `If-Match` is required |
| `internal_error` | 500 | Unexpected; details are in the logs under the request id |

## 4. Lists

- `limit`: 1–50, default 20.
- `cursor`: opaque, taken from the previous page's `next_cursor`.
- Responses: `{"items": [...], "next_cursor": "..."}`. `next_cursor` is absent on the last page.

## 5. Caching and concurrency

- **Public reads** carry `Cache-Control: public, max-age=30` and an `ETag`; `If-None-Match` gets `304 Not Modified`. Everything else is `Cache-Control: no-store`.
- **Concurrent edits:** a product's `ETag` is its version. `PATCH` needs `If-Match`: a stale version gets `412`, and a missing header `428`.
- **Retries:** `Idempotency-Key` is required where a retry must not repeat an effect: order placement, cancellations and staff money actions, from phase 6 ([ADR-010](decisions/ADR-010-idempotency.md)).

## 6. Endpoints

| Endpoint | Access | Phase |
|---|---|---|
| `GET /v1/categories` | Anyone | 3 |
| `GET /v1/products`, `GET /v1/products/{id}` | Anyone | 3 |
| `GET /v1/states` | Anyone | 3 |
| `GET`, `PATCH /v1/me` | `customer` | 3 |
| `GET`, `POST /v1/me/addresses`; `GET`, `PUT`, `DELETE /v1/me/addresses/{id}`; `POST /v1/me/addresses/{id}/default` | `customer` | 3 |
| `POST /v1/admin/catalog/categories`, `PATCH /v1/admin/catalog/categories/{id}` | `admin` | 3 |
| `GET`, `POST /v1/admin/catalog/products`; `GET`, `PATCH /v1/admin/catalog/products/{id}` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/activate`, `/archive` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/variants`, `PATCH …/variants/{variantId}` | `admin` | 3 |
| `POST /v1/admin/catalog/products/{id}/images`, `POST …/images/{imageId}/complete`, `DELETE …/images/{imageId}` | `admin` | 3 |

The OpenAPI document has the request and response schemas. Locally it is also served at `http://localhost:8081/actuator/openapi`.
