# ADR-017: Customer-owned resources are reached only through `/v1/me`

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Requirements FR-CUS3 and §7](../requirements.md#7-security-and-compliance-constraints), [architecture §17](../architecture.md#17-security), [LLD §3.2](../low-level-design.md#32-identity-and-access)

## Context

- Every access to an order, cart or address must be checked against its owner (FR-CUS3, §7). Broken object-level authorization is the most common API vulnerability (OWASP API Security Top 10, API1).
- A customer is identified by the access token's subject. The customer id is internal and is used by other modules.
- Staff (support, admin) act on other people's data through separate, audited endpoints.

## Problem

How do customer-facing endpoints address resources so that one customer can never reach another's?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Customer id in the path (`/v1/customers/{id}/addresses/{addressId}`) plus an ownership check in every handler | Same URL shape for customers and staff | Each handler must remember the check; one omission is a vulnerability; internal ids are exposed |
| **`/v1/me/...`, with the customer resolved from the token** and every lookup scoped to that owner | Ownership by construction: no query runs without the caller's customer id; another customer's resource is simply not found | Staff need their own endpoints under `/v1/admin` |
| A policy engine (OPA, Keycloak authorization services) | Central policies | Another component to run, and object-level checks still need the data |

## Decision

- **Customer-facing resources live under `/v1/me`:** the profile and addresses now; carts and orders in later phases.
- **The customer comes from the token.** No customer-facing endpoint accepts a customer id from the client.
- **Repositories offer only owner-scoped lookups to customer-facing code,** such as `find(customerId, addressId)`, never a bare `find(addressId)`.
- **"Not yours" looks the same as "not found":** both answer `404`, so ids cannot be probed.
- **Staff use `/v1/admin/...`,** with role checks and the audit log.
- **Guests** reach their order through a signed, expiring link (FR-CUS2), designed with checkout.

## Trade-offs

- Staff and customers use different URLs for the same resource.
- A `404` for another customer's resource hides whether it exists, which also hides misuse. Refusals are therefore counted in metrics (phase 12).

## Consequences

- Every phase that adds a customer resource adds a cross-customer test: customer B uses customer A's resource ids and gets `404`.
- Identifiers stay unguessable (UUIDv7) as a second line of defense, not the first.

## Extension (2026-10-02, phase 4)

Guests have carts too. They reach them through `/v1/guest/cart` with a secret cart token in a header, never with a cart id in the path ([ADR-020](ADR-020-guest-cart-tokens.md)). Customers reach theirs through `/v1/me/cart`.
