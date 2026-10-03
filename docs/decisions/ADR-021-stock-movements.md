# ADR-021: Every change to on-hand stock is a recorded movement

- **Status:** Accepted (2026-10-03)
- **Date:** 2026-10-03
- **Related:** [ADR-009](ADR-009-inventory-reservation.md), [requirements FR-INV3, FR-INV7, FR-FUL5](../requirements.md#56-inventory), [LLD §5.8](../low-level-design.md#58-stock-movements)

## Context

- Inventory keeps two counters per SKU and location, `on_hand` and `reserved`, changed only by conditional updates ([ADR-009](ADR-009-inventory-reservation.md)). They are the gate against overselling, so each must stay a single row.
- Warehouse operators record receipts and adjustments with a reason, and each change is audit-logged (FR-INV7).
- Handovers and returns to origin also change `on_hand` (FR-INV3, FR-FUL5).
- Staff need to know why a count is what it is.
- The platform's audit log records staff actions, but not the saga's. It is keyed by target and built as a security record, not for a SKU's history.

## Problem

How are changes to on-hand stock recorded and explained?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Counters only, plus the audit log for staff changes | Nothing new | No record of handovers and returns; history must be pieced together from audit entries; nothing to check the counter against |
| A ledger only, with `on_hand` computed as the sum of movements | One source of truth | No single row to update conditionally. Every reservation would sum the ledger under a lock: slow, and a hot row becomes a hot range |
| **Counters, plus a movement ledger written in the same transaction** | The counters stay the fast gate; the ledger explains every unit and can be reconciled against them | Two writes per change, which must agree |

## Decision

- **Every change to `on_hand` writes one row** to `inventory.stock_movements`, in the same transaction:
  - `RECEIPT`;
  - `ADJUSTMENT`, with a reason: `DAMAGED`, `LOST`, `FOUND` or `COUNT_CORRECTION`;
  - `HANDOVER` and `RETURN`, with the order id.
- **Each movement records** the quantity, `on_hand` after it, the reason or reference, and the actor.
- **`reserved` has no movements:** the `HELD` and `COMMITTED` reservations account for it.
- **Movements are never updated or deleted.**
- **Staff changes are also audit-logged.** The audit log remains the security record of who did what.

## Trade-offs

- **An extra insert per change.** Changes to `on_hand` are rare: receipts, adjustments, and one handover per order. Reservations, the hot path, write no movements.
- **The ledger and the counter could disagree** after a bug or a manual fix. Tests check after every step that `on_hand` equals the sum of the movements, and the same query can run as a periodic reconciliation (phase 12).

## Consequences

- `GET /v1/warehouse/stock/{sku}/movements` shows a SKU's history.
- **Second-layer guards:** `CHECK` constraints match each movement's sign to its kind and reason. A unique index allows one `HANDOVER` and one `RETURN` per order and SKU, even if a status guard failed.
- **Availability events come from the stock item's `version`,** not from movements, because reservations change availability too (`stock.level_changed`, phase 9).
