# ADR-022: Payments and Fulfillment start as simulators behind their real messages

- **Status:** Superseded (2026-10-04): phase 7 replaced the payment simulator with the gateway adapter ([ADR-002](ADR-002-payment-gateway-integration.md)), and phase 8 the shipment simulator with the carrier port and its simulator ([ADR-025](ADR-025-carrier-simulator.md))
- **Date:** 2026-10-03
- **Related:** [ADR-007](ADR-007-saga-orchestration.md), [ADR-002](ADR-002-payment-gateway-integration.md), [implementation plan](../implementation-plan.md), [LLD §6.8](../low-level-design.md#68-participants)

## Context

- Phase 6 builds the order saga. Its exit criteria test every transition and compensation, including late and duplicate replies.
- The saga's participants include Payments (the gateway adapter, hosted checkout, webhooks and polls: phase 7) and Fulfillment (shipments, the carrier and tracking: phase 8).
- The platform refuses to publish a message that nothing handles, so every command the saga sends needs a handler in the production code.
- Tests must drive outcomes the saga reacts to: a payment that succeeds, fails, expires, or is still in flight when the customer cancels; a parcel handed over, delivered or returned.

## Problem

How does the saga get participants that answer its commands before phases 7 and 8 build the real ones?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Test doubles only, in test code | Nothing temporary in production code | The application cannot place an order until phase 8: its commands have no handlers |
| Build phases 7 and 8 first | No temporary code | The saga's needs should shape the participants' contracts, not the other way round; and the gateway's webhooks and the carrier's tracking would arrive with nothing to drive |
| **Simulators inside the Payments and Fulfillment modules, behind the commands and events the saga will always use** | The saga is built and tested against its final contract; the application runs end to end from phase 6; the simulators stay inside their modules | Temporary code, replaced in phases 7 and 8 |

## Decision

- **Payments and Fulfillment handle the saga's commands with simulators** in phase 6: a simulated payment or shipment per order, in each module's own schema.
- **The messages are final:** commands, replies and events are named and shaped as phases 7 and 8 will use them.
- **Tests drive the outcomes** through each module's simulator API (`PaymentSimulator`, `ShipmentSimulator`). There is no HTTP surface for them.
- **Phases 7 and 8 replace the simulators inside their modules.** The saga, and the tests that drive it through messages, do not change.

## Trade-offs

- **Temporary code** in two modules, which phases 7 and 8 delete.
- **The simulators decide instantly:** creating a payment or cancelling a shipment answers in the handler itself. The real participants call external systems from tasks, so their replies come later; the saga already waits for replies with deadlines, so it does not depend on the timing.

## Consequences

- The local stack places orders from phase 6. Without an HTTP surface for the simulators, the demo shows placement, cancellation and rejection; payment success and shipping are shown by tests until phases 7 and 8.
- Phases 7 and 8 must keep the message contracts, or amend them in their LLD with the saga's changes.
