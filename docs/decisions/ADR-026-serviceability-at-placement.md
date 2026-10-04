# ADR-026: Placement checks that the carrier serves the delivery PIN code, from a list kept locally

- **Status:** Accepted (2026-10-04)
- **Date:** 2026-10-04
- **Related:** [ADR-025](ADR-025-carrier-simulator.md), [failure-handling S8](../failure-handling.md#s8-the-carrier-api-is-unavailable), [LLD §6.3](../low-level-design.md#63-placement), [LLD §8.10](../low-level-design.md#810-serviceability)

## Context

- The carrier delivers to some PIN codes only (Q10's unserviceable PIN code).
- Quotes take the delivery state, which their GST needs; placement takes the delivery address (LLD §4.5, §6.3).
- After payment, a booking the carrier refuses leaves a paid order that cannot ship until support cancels it and refunds (S8).
- Placement's latency budget excludes the carrier (NFR-3), and a carrier outage must not stop checkout (FR-FUL4).

## Problem

When does the system find out that the carrier cannot deliver to an address?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| At booking only | Nothing new | The customer pays for an order that cannot ship |
| When quoting | The earliest | Quotes take a state, not an address; the cart would depend on Fulfillment, and the quote API would change |
| **At placement, from a list the carrier adapter keeps** | Refused before payment and before stock is held; no remote call while placing | The list can lag the carrier's |
| At placement, by calling the carrier | Always current | A carrier outage would stop checkout |

## Decision

- **Placement asks Fulfillment whether the delivery PIN code is serviceable,** after its address checks, and refuses with `422 address_not_serviceable` before anything is reserved.
- **The carrier adapter answers from a list it keeps locally.** The simulator serves every PIN code not ending in 2.
- **A refusal at booking stays as the backstop,** for a PIN code that stops being served after placement (LLD §8.5).

## Trade-offs

- **A stale list** lets an order through that the carrier then refuses; support handles it as S8 says. A real adapter refreshes its list from the carrier; the simulator's is fixed.

## Consequences

- Placement has a new error, `address_not_serviceable`; a storefront asks for another address.
- Ordering, which already depends on Fulfillment's messages, also reads its serviceability.
