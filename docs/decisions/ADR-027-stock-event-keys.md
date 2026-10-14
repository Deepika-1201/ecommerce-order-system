# ADR-027: Key stock integration events by SKU and location

Status: Accepted, 2026-10-14 (portfolio milestone P1).

## Context

Stock rows and their versions belong to `(sku, location_code)`. The event model
proposed a compacted stock topic keyed by SKU, with a location in each payload.

## Problem

Compacting by SKU alone discards another location's latest snapshot. A consumer
rebuilding its projection cannot recover all locations, and their independent
version sequences cannot safely be compared as one sequence.

## Options considered

1. Key by SKU and aggregate every location in each event. This needs a shared
   SKU-level version and additional locking across stock rows.
2. Key by SKU and location, matching the existing stock aggregate.
3. Keep SKU keys and disable compaction. Rebuilding then depends on retaining
   every event indefinitely or introducing a separate snapshot mechanism.

## Decision

Use option 2. `aggregate_type` is `stock`; `aggregate_id` and the Kafka key are
the compact JSON serialization of the string array `[sku, location_code]`.
The envelope sequence is that row's version. JSON encoding avoids delimiter
collisions and is available in every consumer's standard JSON library.

The data fields are `sku`, `location_code`, and `available`. Catalog stores and
compares versions per SKU and location. Its projection is a hint only; Inventory
continues to decide reservations transactionally.

## Trade-offs

The key is longer than SKU alone, and topic inspection must account for a JSON
array represented as a string. In return, compaction and event ordering follow
the actual aggregate boundary without additional database coordination.

## Consequences

This refines the stock key in the approved event model. Order event keys remain
order UUIDs. Local single-location deployments use the same contract as a future
multi-location deployment. This does not implement multi-warehouse allocation.

## Revisit when

Availability becomes a single, versioned SKU aggregate across locations, or the
inventory allocation model changes.