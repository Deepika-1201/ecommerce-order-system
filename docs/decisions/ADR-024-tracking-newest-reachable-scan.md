# ADR-024: A shipment applies a carrier scan only if it is newer and reachable

- **Status:** Accepted (2026-10-04)
- **Date:** 2026-10-04
- **Related:** [order lifecycle §4](../order-lifecycle.md#4-related-state-machines), [failure-handling S12](../failure-handling.md#s12-an-old-event-arrives-after-a-newer-one), [requirements FR-FUL3](../requirements.md), [LLD §8.6](../low-level-design.md#86-applying-a-scan)

## Context

- Carriers report a parcel's scans by webhook. The scans come with no ordering at all (S12): late, duplicated or out of order, and some never come.
- The shipment's state machine has a cycle: a failed delivery attempt goes back to `OUT_FOR_DELIVERY` for a reattempt.
- The order's status follows the shipment's milestones: handed over, delivered, return started, back at the warehouse (LLD §6.5).
- A shipment's status never moves backwards (FR-FUL3).
- The domain model's rule applies a scan only if it is the next step from the current status and its carrier time is newer.

## Problem

Which scans does a shipment apply?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A rank per status: apply a higher one | Needs no times | A reattempt's `OUT_FOR_DELIVERY` ranks below `DELIVERY_ATTEMPT_FAILED` and is lost: the domain model's objection |
| The next step only, if newer: the domain model's rule | Follows the machine exactly | A late scan stops the shipment. A `delivered` that arrives before `out_for_delivery` is not the next step, and the late `out_for_delivery` is then older: the order never becomes `DELIVERED` |
| The newest by the carrier's time, whatever its status | Never stalls | A scan with a skewed time could move the shipment back along the machine, such as `picked_up` after `delivered` |
| **Newer by the carrier's time, and reachable along the machine** | Never moves back along the machine; skips what the carrier did not scan; a reattempt applies | Trusts the carrier's clock to order the scans of one parcel |

## Decision

- **A scan applies if its carrier time is after the last applied scan's, and its status is reachable** from the current status along the state machine's arrows. States the carrier did not scan are skipped.
- **Every scan is kept** in the shipment's tracking history, applied or not.
- **Each milestone is published once,** in order, when the shipment first reaches it: a jump from handed over to delivered publishes `ShipmentDelivered`, and one straight to `RTO_DELIVERED` publishes `ShipmentReturnInitiated`, then `ShipmentReturnedToOrigin`.
- **A tie** with the last applied scan's time does not apply.

## Trade-offs

- **The carrier's clock orders the scans.** A scan stamped later than a truly later one wins until a newer scan arrives. The carrier stamps one parcel's scans from its own systems, and the history keeps everything for support.
- **Reachability needs the whole machine** in code, not just its arrows: a small graph search over 12 states.

## Consequences

- Order lifecycle §4's rule is amended: "a valid transition" becomes "reachable".
- The saga already treats a later milestone as the earlier ones (LLD §6.5). Fulfillment publishes each milestone anyway, so every consumer sees all of them.
- A property test delivers itineraries in random order, with duplicates, and checks that the status never moves backwards.
