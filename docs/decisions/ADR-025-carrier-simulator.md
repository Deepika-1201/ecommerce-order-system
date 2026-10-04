# ADR-025: The V1 carrier is a simulator in process, behind the carrier port, with its parcels in the database

- **Status:** Accepted (2026-10-04)
- **Date:** 2026-10-04
- **Related:** [ADR-001](ADR-001-ecosystem-boundaries.md), [ADR-022](ADR-022-simulated-payments-and-fulfillment.md), [requirements Q10](../requirements.md), [LLD §8.9](../low-level-design.md#89-the-carrier-simulator)

## Context

- V1's carrier is simulated, with scripted failures: an outage, an unserviceable PIN code, late or out-of-order tracking, a failed delivery and a return to origin (Q10).
- Unlike the Payment Gateway, no carrier exists in the ecosystem (ADR-001).
- Phase 7's fake gateway runs in process and in memory, and only tests drive it. The real gateway runs the demo.
- The demo and CI need parcels that move on their own once the warehouse hands them over, with no test to drive them.
- The application runs as `api` and `worker` roles, together locally and possibly apart in a deployment.

## Problem

What plays the carrier in V1, and how do its scans reach Fulfillment?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| A carrier service in its own container, sending signed HTTP webhooks | The most realistic boundary | A second application to build, deploy and keep compatible, for a simulator, with no ecosystem project to own it |
| In process and in memory, like the fake gateway | Little code; tests drive it | Parcels vanish on a restart, and each instance of a split deployment would hold different ones |
| **In process, behind the `Carrier` port, with its parcels in the fulfillment schema** | One deployable; parcels survive restarts and are shared by instances; its scans use the webhook's intake | The webhook's HTTP and signature path is not on the simulator's path and needs its own tests; simulator code ships in the image |

## Decision

- **The simulator implements the `Carrier` port** in `fulfillment.carrier`, and keeps its parcels in `fulfillment.simulated_parcels`.
- **Its scans enter the platform's webhook inbox** through the same intake as a verified carrier webhook, without HTTP or signatures, as the fake gateway's events do.
- **The delivery PIN code's last digit picks the scenario**, so test and demo data choose it through the address.
- **It advances parcels on its own** when `ecom.fulfillment.simulator.auto-advance` is on, as in the compose stacks: a scan every 5 seconds once the warehouse has handed a parcel over. Tests leave it off and drive it through `ShipmentSimulator`.

## Trade-offs

- **The simulator watches Fulfillment's shipments** to see a handover, a shortcut a real carrier does not have: its driver sees the parcel.
- **A signed webhook endpoint with no V1 sender** other than tests and the demo's forged request. It is the contract a real carrier's adapter would use.

## Consequences

- A real carrier is an adapter for the same port, with its own webhook scheme in front of the same inbox. The simulator stays for tests and local runs.
- The phase 6 shipment simulator and its table are removed (ADR-022).
