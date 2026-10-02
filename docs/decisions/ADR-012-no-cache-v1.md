# ADR-012: No cache tier in V1

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Consistency model §4](../consistency-model.md#4-caching), [ADR-009](ADR-009-inventory-reservation.md), [architecture §16](../architecture.md#16-scalability)

## Context

The brief asks to evaluate caching for catalog, availability, sessions, carts, pricing and order data, and to avoid caching where correctness would suffer. Peak reads are about 5,000 catalog requests per second (NFR-1). Payment-Orchestrator also chose no Redis in V1 (its ADR-011).

## Problem

Does V1 need a cache, and where?

## Options considered

| Data | Redis | Local cache (Caffeine) | HTTP caching at the edge | None |
|---|---|---|---|---|
| Catalog pages | Fast, but needs invalidation | Fast, stale per instance | Cheap for anonymous pages | PostgreSQL with indexes handles 5,000 reads/s |
| Availability | Must never decide checkout | Same | Same | Checkout reads the stock row |
| Sessions | Not needed: tokens are stateless JWTs | — | — | — |
| Carts | A second source of truth for durable data | — | — | PostgreSQL |
| Prices and quotes | Stale prices are a correctness bug | — | — | PostgreSQL |
| Orders | Read-your-writes required | — | — | PostgreSQL |

## Decision

**No cache tier in V1** (proposed).

- Catalog reads come from PostgreSQL. Anonymous product pages carry `Cache-Control` and ETags, so the edge or a CDN can absorb read storms.
- Sessions are stateless JWTs.
- Checkout, stock, prices, carts and orders always read their source of truth.
- The only cached value is the flash-sale sold-out flag ([ADR-009](ADR-009-inventory-reservation.md)), per instance, for 1 second. It can only reject early, never accept.

Redis is reconsidered when load tests show PostgreSQL or a read replica cannot serve the catalog, or when phase 16 chooses Redis for the waiting room.

## Trade-offs

- More database reads than with a cache; at NFR-1 that is well within one primary plus a replica.
- No ready-made distributed rate limiter; edge limits and per-instance limits cover V1.

## Consequences

- No cache invalidation logic and no stale-cache bugs in V1.
- The consistency model has a single rule: correctness-sensitive reads go to the source of truth.
