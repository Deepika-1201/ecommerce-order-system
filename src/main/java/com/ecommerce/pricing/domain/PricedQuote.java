package com.ecommerce.pricing.domain;

import com.ecommerce.pricing.TaxRegime;
import java.util.List;

/** The arithmetic of a quote, in paise; every total is the sum of its parts (ADR-019). */
record PricedQuote(TaxRegime regime, List<Line> lines, Charge shipping, Totals totals) {

    PricedQuote {
        lines = List.copyOf(lines);
    }

    /** {@code amount = gross − discount}; {@code taxable value = amount − tax}. */
    record Line(long grossPaise, long discountPaise, long amountPaise, int gstRateBps, Tax tax) {

        long taxableValuePaise() {
            return amountPaise - tax.totalPaise();
        }
    }

    record Charge(long amountPaise, int gstRateBps, Tax tax) {

        long taxableValuePaise() {
            return amountPaise - tax.totalPaise();
        }
    }

    /** {@code grand total = goods + shipping = taxable value + tax}. */
    record Totals(
            long grossPaise,
            long discountPaise,
            long goodsPaise,
            long shippingPaise,
            long taxableValuePaise,
            Tax tax,
            long grandTotalPaise) {
    }
}
