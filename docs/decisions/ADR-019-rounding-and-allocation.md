# ADR-019: Integer paise, rounding per line and component, largest-remainder discount allocation

- **Status:** Accepted (2026-10-02)
- **Date:** 2026-10-02
- **Related:** [Requirements FR-CHK1, FR-CHK5](../requirements.md#54-checkout-and-pricing), [ADR-018](ADR-018-gst-inclusive-prices.md), [LLD §4.7](../low-level-design.md#47-rounding-and-allocation)

## Context

- Amounts are integer paise, and the rounding rules for tax and discount allocation are documented and tested (FR-CHK5). Phase 4's exit criterion is that totals equal the sum of their parts.
- A coupon discount applies to the whole order but is allocated to lines (FR-CHK1), because each line's tax depends on its own amount, and partial refunds (V2) refund lines.
- Intra-state supplies show CGST and SGST, which are equal by law.

## Problem

How are amounts rounded, and how is an order-level discount split across lines, so that every total adds up to the paisa?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| Floating point | None for money | Inexact; rejected outright |
| Round totals independently of lines | Each figure is individually "correct" | Totals stop matching the sums of their lines and need adjustment lines |
| **Round each line and tax component; totals are sums** | Totals always add up, by construction | A total can differ by a few paise from the rounded exact total |
| Discount: remainder to the last line | Simple | The last line absorbs every rounding error, depending on line order |
| Discount: all of it on the most expensive line | Simple | Distorts that line's tax, and can exceed its amount |
| **Discount: proportional, largest remainder** | Fair; each share within a paisa of exact; deterministic | Slightly more code |
| Half-even rounding | No statistical bias over many sums | Unfamiliar on invoices; harder to check by hand |
| **Half-up rounding** | The convention customers and accountants check by hand | Up to half a paisa of upward bias per component |

## Decision

- **Integer paise** in `long`, with exact rational arithmetic and `BigInteger` where a product could overflow.
- **Percentage discounts round down** before their cap, and no discount exceeds the gross subtotal minus ₹1.
- **Allocation:** in proportion to each line's gross amount, by the largest-remainder method, earlier lines first on ties.
- **Tax:** rounded half up per component. For intra-state supplies, one half is computed and used for both CGST and SGST. The taxable value is the line amount minus its tax.
- **Totals are sums** of the rounded parts, never rounded themselves.

## Trade-offs

- Recomputing a line's tax from its taxable value can differ from the stored tax by up to one paisa per component. The inclusive line amount, which the customer pays, is the source of truth.
- Intra-state tax on a line can be one paisa more than inter-state tax on the same line, because it is two rounded halves.

## Consequences

- Property tests (jqwik) check the identities on random carts, and two worked examples in the LLD are unit tests to the paisa.
- Partial refunds (V2) can return a line's exact amounts, because nothing exists only at the total level.
