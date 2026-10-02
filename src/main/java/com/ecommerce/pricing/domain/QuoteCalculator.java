package com.ecommerce.pricing.domain;

import com.ecommerce.pricing.TaxRegime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prices a cart: gross amounts, the coupon discount allocated to lines, GST per line and on shipping, and totals
 * that are sums of their parts (LLD §4.5–4.8). Pure: no I/O, no clock.
 */
final class QuoteCalculator {

    /** No discount takes the goods below ₹1, so every payment is at least that. */
    static final long MINIMUM_GOODS_PAISE = 100;

    private QuoteCalculator() {
    }

    static PricedQuote price(PricingInput input) {
        TaxRegime regime = TaxRegime.of(input.supplyState(), input.deliveryState());
        long[] gross = input.lines().stream()
                .mapToLong(line -> Math.multiplyExact(line.unitPricePaise(), line.quantity()))
                .toArray();
        long grossTotal = sum(gross);
        long discount = discount(input.discount(), grossTotal);
        long[] shares = Allocation.largestRemainder(discount, gross);

        List<PricedQuote.Line> lines = new ArrayList<>();
        Tax lineTax = Tax.NONE;
        long taxableValue = 0;
        int highestRate = 0;
        for (int i = 0; i < gross.length; i++) {
            PricingInput.Line line = input.lines().get(i);
            long amount = gross[i] - shares[i];
            int rate = GstRates.rateBps(line.gstCategory(), amount, line.quantity());
            PricedQuote.Line priced = new PricedQuote.Line(gross[i], shares[i], amount, rate,
                    GstRates.extract(amount, rate, regime));
            lines.add(priced);
            lineTax = lineTax.plus(priced.tax());
            taxableValue += priced.taxableValuePaise();
            highestRate = Math.max(highestRate, rate);
        }

        long goods = grossTotal - discount;
        long fee = goods >= input.shipping().freeFromPaise() ? 0 : input.shipping().feePaise();
        PricedQuote.Charge shipping = new PricedQuote.Charge(fee, highestRate,
                GstRates.extract(fee, highestRate, regime));
        PricedQuote.Totals totals = new PricedQuote.Totals(grossTotal, discount, goods, fee,
                taxableValue + shipping.taxableValuePaise(), lineTax.plus(shipping.tax()), goods + fee);
        return new PricedQuote(regime, lines, shipping, totals);
    }

    private static long discount(DiscountRule rule, long grossTotal) {
        if (rule == null) {
            return 0;
        }
        long ceiling = Math.max(0, grossTotal - MINIMUM_GOODS_PAISE);
        return Math.max(0, Math.min(rule.discountOn(grossTotal), ceiling));
    }

    private static long sum(long[] amounts) {
        long total = 0;
        for (long amount : amounts) {
            total = Math.addExact(total, amount);
        }
        return total;
    }
}
