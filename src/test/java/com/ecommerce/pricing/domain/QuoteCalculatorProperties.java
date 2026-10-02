package com.ecommerce.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.pricing.domain.PricingInput.Line;
import com.ecommerce.pricing.domain.PricingInput.ShippingRule;
import com.ecommerce.shared.IndianState;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The phase 4 exit criterion: totals are the sums of their parts, and the rounding rules hold (ADR-019). */
class QuoteCalculatorProperties {

    @Property
    void totalsAreTheSumsOfTheirParts(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);
        PricedQuote.Totals totals = quote.totals();
        Tax tax = quote.shipping().tax();
        long gross = 0;
        long discount = 0;
        long goods = 0;
        long taxable = quote.shipping().taxableValuePaise();
        for (PricedQuote.Line line : quote.lines()) {
            gross += line.grossPaise();
            discount += line.discountPaise();
            goods += line.amountPaise();
            taxable += line.taxableValuePaise();
            tax = tax.plus(line.tax());
        }

        assertThat(totals.grossPaise()).isEqualTo(gross);
        assertThat(totals.discountPaise()).isEqualTo(discount);
        assertThat(totals.goodsPaise()).isEqualTo(goods).isEqualTo(gross - discount);
        assertThat(totals.shippingPaise()).isEqualTo(quote.shipping().amountPaise());
        assertThat(totals.tax()).isEqualTo(tax);
        assertThat(totals.taxableValuePaise()).isEqualTo(taxable);
        assertThat(totals.grandTotalPaise()).isEqualTo(goods + totals.shippingPaise())
                .isEqualTo(taxable + tax.totalPaise());
    }

    @Property
    void eachLineAddsUp(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);

        for (int i = 0; i < quote.lines().size(); i++) {
            Line line = input.lines().get(i);
            PricedQuote.Line priced = quote.lines().get(i);
            assertThat(priced.grossPaise()).isEqualTo(line.unitPricePaise() * line.quantity());
            assertThat(priced.discountPaise()).isBetween(0L, priced.grossPaise());
            assertThat(priced.amountPaise()).isEqualTo(priced.grossPaise() - priced.discountPaise());
            assertThat(priced.taxableValuePaise()).isNotNegative();
            assertThat(priced.taxableValuePaise() + priced.tax().totalPaise()).isEqualTo(priced.amountPaise());
        }
        assertThat(quote.shipping().taxableValuePaise()).isNotNegative();
    }

    @Property
    void thePlaceOfSupplyDecidesTheTaxComponents(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);
        boolean sameState = input.supplyState() == input.deliveryState();

        assertThat(quote.regime()).isEqualTo(sameState ? TaxRegime.INTRA_STATE : TaxRegime.INTER_STATE);
        for (Tax tax : taxes(quote)) {
            if (sameState) {
                assertThat(tax.igstPaise()).isZero();
                assertThat(tax.cgstPaise()).isEqualTo(tax.sgstPaise());
            } else {
                assertThat(tax.cgstPaise()).isZero();
                assertThat(tax.sgstPaise()).isZero();
            }
        }
    }

    @Property
    void discountSharesAreEachWithinAPaisaOfExact(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);
        BigInteger gross = BigInteger.valueOf(quote.totals().grossPaise());
        BigInteger discount = BigInteger.valueOf(quote.totals().discountPaise());

        for (PricedQuote.Line line : quote.lines()) {
            BigInteger error = BigInteger.valueOf(line.discountPaise()).multiply(gross)
                    .subtract(discount.multiply(BigInteger.valueOf(line.grossPaise())));
            assertThat(error.abs()).isLessThan(gross);
        }
    }

    @Property
    void eachTaxComponentIsWithinHalfAPaisaOfExact(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);

        for (PricedQuote.Line line : quote.lines()) {
            assertRoundedHalfUp(quote.regime(), line.amountPaise(), line.gstRateBps(), line.tax());
        }
        assertRoundedHalfUp(quote.regime(), quote.shipping().amountPaise(), quote.shipping().gstRateBps(),
                quote.shipping().tax());
    }

    @Property
    void theRateFollowsTheCategoryAndTheValuePerPiece(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);

        for (int i = 0; i < quote.lines().size(); i++) {
            Line line = input.lines().get(i);
            PricedQuote.Line priced = quote.lines().get(i);
            int expected = switch (line.gstCategory()) {
                case EXEMPT -> 0;
                case REDUCED -> 500;
                case STANDARD -> 1_800;
                case DEMERIT -> 4_000;
                case APPAREL, FOOTWEAR -> priced.amountPaise() <= 262_500L * line.quantity() ? 500 : 1_800;
            };
            assertThat(priced.gstRateBps()).isEqualTo(expected);
        }
    }

    @Property
    void theDiscountIsTheRuleBoundedByTheOneRupeeFloor(@ForAll("inputs") PricingInput input) {
        PricedQuote.Totals totals = QuoteCalculator.price(input).totals();
        long gross = totals.grossPaise();
        long rule = switch (input.discount()) {
            case null -> 0;
            case DiscountRule.Flat flat -> flat.amountPaise();
            case DiscountRule.Percent percent -> {
                long exactlyRoundedDown = BigInteger.valueOf(gross).multiply(BigInteger.valueOf(percent.basisPoints()))
                        .divide(BigInteger.valueOf(10_000)).longValueExact();
                yield percent.capPaise() == null ? exactlyRoundedDown : Math.min(exactlyRoundedDown, percent.capPaise());
            }
        };

        assertThat(totals.discountPaise()).isEqualTo(Math.min(rule, Math.max(0, gross - 100)));
        assertThat(totals.goodsPaise()).isGreaterThanOrEqualTo(Math.min(gross, 100));
    }

    @Property
    void shippingFollowsItsRuleAtTheHighestLineRate(@ForAll("inputs") PricingInput input) {
        PricedQuote quote = QuoteCalculator.price(input);
        ShippingRule rule = input.shipping();
        int highest = quote.lines().stream().mapToInt(PricedQuote.Line::gstRateBps).max().orElseThrow();

        assertThat(quote.shipping().amountPaise())
                .isEqualTo(quote.totals().goodsPaise() >= rule.freeFromPaise() ? 0 : rule.feePaise());
        assertThat(quote.shipping().gstRateBps()).isEqualTo(highest);
    }

    @Provide
    Arbitrary<PricingInput> inputs() {
        Arbitrary<Line> line = Combinators.combine(
                        Arbitraries.integers().between(1, 10), prices(), Arbitraries.of(GstCategory.class))
                .as(Line::new);
        Arbitrary<DiscountRule> percent = Combinators.combine(Arbitraries.integers().between(1, 10_000),
                        Arbitraries.longs().between(1, 1_000_000_000L).injectNull(0.5))
                .as(DiscountRule.Percent::new);
        Arbitrary<DiscountRule> flat = Arbitraries.longs().between(1, 1_000_000_000_000L).map(DiscountRule.Flat::new);
        Arbitrary<DiscountRule> discount = Arbitraries.oneOf(percent, flat).injectNull(0.25);
        Arbitrary<ShippingRule> shipping = Combinators.combine(
                        Arbitraries.longs().between(0, 100_000), Arbitraries.longs().between(0, 1_000_000_000L))
                .as(ShippingRule::new);
        Arbitrary<IndianState> state = Arbitraries.of(IndianState.class);
        return Combinators.combine(line.list().ofMinSize(1).ofMaxSize(50), discount, shipping, state, state,
                        Arbitraries.of(true, false))
                .as((lines, rule, fee, supply, delivery, sameState) ->
                        new PricingInput(lines, rule, fee, supply, sameState ? supply : delivery));
    }

    /** Anywhere up to ₹1 crore, often realistic, and often near the apparel slab boundary. */
    private static Arbitrary<Long> prices() {
        return Arbitraries.oneOf(
                Arbitraries.longs().between(1, 1_000_000_000L),
                Arbitraries.longs().between(100, 1_000_000),
                Arbitraries.longs().between(250_000, 275_000));
    }

    /** Each component against its exact value: the full rate for IGST, half of it for CGST and for SGST. */
    private static void assertRoundedHalfUp(TaxRegime regime, long amount, int rateBps, Tax tax) {
        BigInteger scaled = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(rateBps));
        if (regime == TaxRegime.INTER_STATE) {
            assertWithinHalf(tax.igstPaise(), scaled, 10_000L + rateBps);
        } else {
            assertWithinHalf(tax.cgstPaise(), scaled, 2 * (10_000L + rateBps));
            assertWithinHalf(tax.sgstPaise(), scaled, 2 * (10_000L + rateBps));
        }
    }

    /** {@code |rounded − numerator / denominator| ≤ 1/2}, in integers. */
    private static void assertWithinHalf(long rounded, BigInteger numerator, long denominator) {
        BigInteger d = BigInteger.valueOf(denominator);
        BigInteger twiceError = BigInteger.valueOf(rounded).multiply(d).subtract(numerator).multiply(BigInteger.TWO);
        assertThat(twiceError.abs()).isLessThanOrEqualTo(d);
    }

    private static List<Tax> taxes(PricedQuote quote) {
        return Stream.concat(quote.lines().stream().map(PricedQuote.Line::tax), Stream.of(quote.shipping().tax()))
                .toList();
    }
}
