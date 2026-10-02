package com.ecommerce.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.pricing.TaxRegime;
import org.junit.jupiter.api.Test;

class GstRatesTests {

    @Test
    void fixedRatesFollowTheCategory() {
        assertThat(GstRates.rateBps(GstCategory.EXEMPT, 100_000, 1)).isZero();
        assertThat(GstRates.rateBps(GstCategory.REDUCED, 100_000, 1)).isEqualTo(500);
        assertThat(GstRates.rateBps(GstCategory.STANDARD, 100_000, 1)).isEqualTo(1_800);
        assertThat(GstRates.rateBps(GstCategory.DEMERIT, 100_000, 1)).isEqualTo(4_000);
    }

    @Test
    void apparelAndFootwearChangeSlabAboveRs2625PerPieceIncludingTax() {
        for (GstCategory category : new GstCategory[] {GstCategory.APPAREL, GstCategory.FOOTWEAR}) {
            assertThat(GstRates.rateBps(category, 262_500, 1)).as(category + " at ₹2,625.00").isEqualTo(500);
            assertThat(GstRates.rateBps(category, 262_501, 1)).as(category + " at ₹2,625.01").isEqualTo(1_800);
            assertThat(GstRates.rateBps(category, 525_000, 2)).as(category + " at 2 × ₹2,625.00").isEqualTo(500);
            assertThat(GstRates.rateBps(category, 525_001, 2)).as(category + " just over").isEqualTo(1_800);
        }
    }

    @Test
    void taxIsExtractedFromTheInclusiveAmountAndRoundedHalfUp() {
        assertThat(GstRates.extract(118_00, 1_800, TaxRegime.INTER_STATE)).isEqualTo(new Tax(0, 0, 18_00));
        assertThat(GstRates.extract(118_00, 1_800, TaxRegime.INTRA_STATE)).isEqualTo(new Tax(9_00, 9_00, 0));
        assertThat(GstRates.extract(34_900, 500, TaxRegime.INTER_STATE).igstPaise()).as("1,661.90").isEqualTo(1_662);
        assertThat(GstRates.extract(55_427, 1_800, TaxRegime.INTRA_STATE).cgstPaise()).as("4,227.48")
                .isEqualTo(4_227);
        assertThat(GstRates.extract(500_000, 0, TaxRegime.INTRA_STATE)).isEqualTo(Tax.NONE);
    }

    @Test
    void halfUpRoundsHalvesAwayFromZero() {
        assertThat(GstRates.halfUp(5, 2)).isEqualTo(3);
        assertThat(GstRates.halfUp(7, 2)).isEqualTo(4);
        assertThat(GstRates.halfUp(4, 3)).isEqualTo(1);
        assertThat(GstRates.halfUp(5, 3)).isEqualTo(2);
        assertThat(GstRates.halfUp(0, 7)).isZero();
    }
}
