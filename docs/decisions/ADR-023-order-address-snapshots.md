# ADR-023: Orders reference immutable address snapshots kept in the customer schema

- **Status:** Accepted (2026-10-03)
- **Date:** 2026-10-03
- **Related:** [ADR-005](ADR-005-postgresql.md), [ADR-017](ADR-017-customer-resources-under-me.md), [requirements FR-ORD1](../requirements.md#55-orders), [database.md](../database.md), [LLD §6.3](../low-level-design.md#63-placement)

## Context

- An order keeps the addresses it was placed with, unchanged by later edits or deletions (FR-ORD1).
- Personal data (names, phones, addresses) lives only in the `customer` schema, so that account deletion can anonymize it in one place (phase 13). Messages carry ids, never addresses (event model §2).
- Customers edit and delete their saved addresses at any time.

## Problem

Where does an order's copy of its delivery and billing addresses live?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Copy the address into the `ordering` schema | One read renders an order | Personal data in two schemas; anonymization must reach into Ordering, and every future module that copies addresses |
| Reference the saved address | Nothing copied | Edits and deletions rewrite history: an order would show where the customer lives now, not where it was sent |
| **Immutable snapshots in the `customer` schema, referenced by id** | Personal data stays in one schema; history never changes | Reading an order's addresses takes a call to Customer |

## Decision

- **Placement asks Customer for a snapshot** of each address it uses, in the placement transaction. The snapshot is a new row in `customer.address_snapshots`, never updated.
- **The order stores the snapshot ids,** plus the delivery state code, which the quote's GST already depends on.
- **Reads compose:** the order API fetches the snapshots from Customer to show them.

## Trade-offs

- **An extra lookup** per order read, by primary key. Lists of orders show no addresses, so they need none.
- **A snapshot per placement,** even when the address is unchanged. Snapshots are small, and deduplicating them would tie unrelated orders together.
- **Extraction:** if Ordering becomes a service (not planned before V3), the call becomes an API call, which is the boundary this decision draws anyway.

## Consequences

- Account deletion (phase 13) anonymizes snapshots with the saved addresses, keeping the state code and PIN code, which tax records and delivery analysis need.
- Fulfillment (phase 8) reads the delivery snapshot from Customer to book a shipment.
