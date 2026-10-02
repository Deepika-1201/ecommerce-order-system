package com.ecommerce.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.pricing.domain.PricingInput.Line;
import com.ecommerce.pricing.domain.PricingInput.ShippingRule;
import com.ecommerce.shared.IndianState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The worked examples of LLD §4.7, to the paisa. */
class QuoteCalculatorTests {

    private static final ShippingRule SHIPPING = new ShippingRule(4_900, 49_900);

    @Test
    void workedExampleOneIntraStateWithACappedCoupon() {
        PricedQuote quote = QuoteCalculator.price(new PricingInput(
                List.of(new Line(2, 129_900, GstCategory.APPAREL),
                        new Line(1, 349_900, GstCategory.FOOTWEAR),
                        new Line(1, 59_900, GstCategory.STANDARD)),
                new DiscountRule.Percent(1_000, 50_000L), SHIPPING, IndianState.KARNATAKA, IndianState.KARNATAKA));

        assertThat(quote.regime()).isEqualTo(TaxRegime.INTRA_STATE);
        assertThat(quote.lines()).containsExactly(
                new PricedQuote.Line(259_800, 19_400, 240_400, 500, new Tax(5_724, 5_724, 0)),
                new PricedQuote.Line(349_900, 26_127, 323_773, 1_800, new Tax(24_695, 24_695, 0)),
                new PricedQuote.Line(59_900, 4_473, 55_427, 1_800, new Tax(4_227, 4_227, 0)));
        assertThat(quote.lines()).extracting(PricedQuote.Line::taxableValuePaise)
                .containsExactly(228_952L, 274_383L, 46_973L);
        assertThat(quote.shipping()).isEqualTo(new PricedQuote.Charge(0, 1_800, Tax.NONE));
        assertThat(quote.totals()).isEqualTo(new PricedQuote.Totals(669_600, 50_000, 619_600, 0, 550_308,
                new Tax(34_646, 34_646, 0), 619_600));
    }

    @Test
    void workedExampleTwoInterStateWithShipping() {
        PricedQuote quote = QuoteCalculator.price(new PricingInput(
                List.of(new Line(1, 34_900, GstCategory.REDUCED)),
                null, SHIPPING, IndianState.KARNATAKA, IndianState.MAHARASHTRA));

        assertThat(quote.regime()).isEqualTo(TaxRegime.INTER_STATE);
        assertThat(quote.lines()).containsExactly(new PricedQuote.Line(34_900, 0, 34_900, 500, new Tax(0, 0, 1_662)));
        assertThat(quote.lines().getFirst().taxableValuePaise()).isEqualTo(33_238);
        assertThat(quote.shipping()).isEqualTo(new PricedQuote.Charge(4_900, 500, new Tax(0, 0, 233)));
        assertThat(quote.shipping().taxableValuePaise()).isEqualTo(4_667);
        assertThat(quote.totals()).isEqualTo(new PricedQuote.Totals(34_900, 0, 34_900, 4_900, 37_905,
                new Tax(0, 0, 1_895), 39_800));
    }

    @Test
    void aDiscountShareCanMoveApparelIntoTheLowerSlab() {
        PricingInput withoutCoupon = new PricingInput(List.of(new Line(1, 269_900, GstCategory.APPAREL)), null,
                SHIPPING, IndianState.KARNATAKA, IndianState.KARNATAKA);
        PricingInput withCoupon = new PricingInput(withoutCoupon.lines(), new DiscountRule.Flat(10_000), SHIPPING,
                IndianState.KARNATAKA, IndianState.KARNATAKA);

        assertThat(QuoteCalculator.price(withoutCoupon).lines().getFirst().gstRateBps()).isEqualTo(1_800);
        assertThat(QuoteCalculator.price(withCoupon).lines().getFirst().gstRateBps()).isEqualTo(500);
    }

    @Test
    void aDiscountNeverTakesTheGoodsBelowOneRupee() {
        PricedQuote quote = QuoteCalculator.price(new PricingInput(List.of(new Line(1, 30_000, GstCategory.STANDARD)),
                new DiscountRule.Flat(50_000), SHIPPING, IndianState.KARNATAKA, IndianState.KARNATAKA));

        assertThat(quote.totals().discountPaise()).isEqualTo(29_900);
        assertThat(quote.totals().goodsPaise()).isEqualTo(100);
    }

    @Test
    void shippingOnExemptGoodsIsUntaxed() {
        PricedQuote quote = QuoteCalculator.price(new PricingInput(List.of(new Line(1, 20_000, GstCategory.EXEMPT)),
                null, SHIPPING, IndianState.KARNATAKA, IndianState.KERALA));

        assertThat(quote.shipping()).isEqualTo(new PricedQuote.Charge(4_900, 0, Tax.NONE));
    }

    @Test
    void shippingIsFreeFromTheThresholdItself() {
        PricedQuote atThreshold = QuoteCalculator.price(new PricingInput(
                List.of(new Line(1, 49_900, GstCategory.STANDARD)), null, SHIPPING, IndianState.KARNATAKA,
                IndianState.KARNATAKA));
        PricedQuote justBelow = QuoteCalculator.price(new PricingInput(
                List.of(new Line(1, 49_899, GstCategory.STANDARD)), null, SHIPPING, IndianState.KARNATAKA,
                IndianState.KARNATAKA));

        assertThat(atThreshold.totals().shippingPaise()).isZero();
        assertThat(justBelow.totals().shippingPaise()).isEqualTo(4_900);
    }
}
