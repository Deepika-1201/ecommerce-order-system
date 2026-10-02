# ADR-020: Guest carts are opened by a secret cart token in a header

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [ADR-017](ADR-017-customer-resources-under-me.md) (extended by this ADR), [Requirements Q6, FR-CRT2, FR-CUS3](../requirements.md#53-cart), [LLD §4.4](../low-level-design.md#44-guest-carts-and-merging)

## Context

- Guests can shop without an account and have server-side carts (Q6), which merge into the customer's cart at sign-in (FR-CRT2).
- Every access to a cart is checked against its owner (FR-CUS3). [ADR-017](ADR-017-customer-resources-under-me.md) does this for customers through `/v1/me` and the access token; guests have no access token.
- Clients are API clients and demo scripts, not browsers (Q16).
- A guest cart holds no personal data: guest contact details arrive with checkout (phase 6).

## Problem

How does a guest prove that a cart is theirs?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| The cart id in the path, with no other check | Simple | Whoever learns the id has the cart; ids end up in logs and links (broken object-level authorization, by design) |
| A signed token (JWT or HMAC) carrying the cart id | Verified without a lookup | Cannot be revoked before it expires, yet merging must end it; signing keys to manage |
| Anonymous users in Keycloak | One authentication model | An identity-provider user for every visitor; heavier flows for a cart |
| A cookie session | Browser-native | No browser client; would need CSRF protection |
| **A random 256-bit token, stored as a hash, sent in a header** | Unguessable; revoked by deleting the cart; a database leak reveals no usable token; headers stay out of URLs and access logs | One more kind of credential; losing it loses the cart |

## Decision

- **`POST /v1/guest/cart` returns a cart token once:** 32 bytes from a secure random generator, base64url-encoded.
- **Guest endpoints live under `/v1/guest/cart`** and take the token in the `Cart-Token` header, never in a path or query string.
- **Only its SHA-256 hash is stored,** and the token is never logged. A hash is enough because the token is random: there is nothing to guess.
- **An unknown token is `404`,** like any resource that is not the caller's.
- **The token ends with its cart:** after 30 days without activity, or when the cart is merged into a customer's.

## Trade-offs

- The token is a bearer credential: whoever holds it holds the cart. That is acceptable for a cart without personal data.
- A guest cart cannot follow a guest to another device. Signing in, and merging, is the way to keep a cart.

## Consequences

- [ADR-017](ADR-017-customer-resources-under-me.md) is extended: customers reach their carts through `/v1/me/cart`, and guests through `/v1/guest/cart` with the cart token. Neither path carries a cart id.
- Guest orders are reached through a signed, expiring link (FR-CUS2, phase 6), not through the cart token.
- Phase 13 adds a rate limit on creating guest carts.
