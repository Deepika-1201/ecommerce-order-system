# ADR-018: GST is extracted per line from GST-inclusive prices

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Requirements FR-CHK1, FR-ORD1, §7](../requirements.md#54-checkout-and-pricing), [ADR-019](ADR-019-rounding-and-allocation.md), [LLD §4.6](../low-level-design.md#46-gst)

## Context

- The store sells to consumers in India (Q2). Consumers expect the displayed price to be what they pay, and packaged goods must show a maximum retail price that includes all taxes.
- A quote shows GST per line: CGST and SGST when the warehouse and the delivery address are in the same state, IGST otherwise (FR-CHK1). Orders keep that breakdown (FR-ORD1), and partial refunds will need it per line (V2).
- Catalog classifies each product into a GST category (phase 3). GST 2.0, in force since 22 September 2025, uses rates of 5%, 18% and 40%, plus exempt goods. Apparel and footwear are taxed at 5% up to ₹2,500 per piece or pair, and at 18% above.
- A shipping fee is charged on the same order as the goods.

## Problem

How is GST computed for each line and for shipping?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Prices exclude GST, and tax is added at checkout | Simple arithmetic; the familiar invoice layout | The price paid differs from the price shown; packaged goods must show an inclusive price anyway |
| **Prices include GST; tax is extracted per line after its discount share** | The price shown is the price paid; the per-line breakdown exists from the start | Extraction rounds, and recomputing tax from the taxable value can differ by a paisa; the apparel threshold needs a rule for inclusive prices |
| Prices include GST; tax is computed on the order total, then apportioned to lines | One rounding step | Lines with different rates cannot share one computation, and apportioning tax back to lines needs its own rounding rule |

## Decision

- **List prices include GST.** Each line's tax is extracted from its amount after its discount share ([ADR-019](ADR-019-rounding-and-allocation.md) defines the rounding).
- **Rates by GST category:** `EXEMPT` 0%, `REDUCED` 5%, `STANDARD` 18%, `DEMERIT` 40%. `APPAREL` and `FOOTWEAR` are 5% when the value per piece or pair, excluding GST, is at most ₹2,500, and 18% otherwise.
- **The threshold is tested at the lower rate,** on the value per piece after the discount share: a line qualifies when it costs at most ₹2,625 per piece, compared in integers as `amount ≤ 262,500 × quantity`. An item priced between ₹2,625 and ₹2,950 is therefore taxed at 18%, even though its value excluding tax at 18% would be under ₹2,500, because the 5% rate needs a value under ₹2,500 when taxed at 5%.
- **Place of supply:** the warehouse's state (configuration; one warehouse in V1) against the delivery state. The same state gives CGST and SGST at half the rate each; different states give IGST.
- **Shipping is a separate charge,** taxed at the highest rate among the order's lines, or untaxed if every line is exempt.
- **Rates are code,** with their effective date and tests, not configuration.

## Trade-offs

- **Shipping at the highest rate** can collect slightly more tax than apportioning the fee across lines at their own rates. The customer pays the same inclusive fee either way; only the split between taxable value and tax changes. Apportioning can replace this rule without changing the shape of a quote.
- **Rate changes need a release.** The GST Council announces them weeks ahead, and a change to tax logic deserves review and tests anyway.
- **Not modeled:** HSN codes, compensation cess, and B2B invoices with the buyer's GSTIN. Tax invoices are V2 (requirements §7).

## Consequences

- Catalog list prices are GST-inclusive, and api.md says so.
- Ordering copies the per-line breakdown into the order in phase 6, and refunds in V2 can return a line's exact tax.
- A tax adviser reviews these rules before production (phase 18).
