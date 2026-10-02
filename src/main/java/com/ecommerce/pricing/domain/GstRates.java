package com.ecommerce.pricing.domain;

import com.ecommerce.catalog.GstCategory;
import com.ecommerce.pricing.TaxRegime;
import java.time.LocalDate;

/**
 * GST rates and tax extraction from GST-inclusive amounts (ADR-018), rounded half up per component (ADR-019). Rates
 * are basis points: 1800 is 18%.
 */
final class GstRates {

    static final LocalDate EFFECTIVE_FROM = LocalDate.of(2025, 9, 22);
    static final int EXEMPT = 0;
    static final int MERIT = 500;
    static final int STANDARD = 1_800;
    static final int DEMERIT = 4_000;

    /** ₹2,500 per piece plus 5%: apparel and footwear at or under it, after discount, are taxed at 5%. */
    static final long LOWER_SLAB_LIMIT_PER_PIECE_PAISE = 262_500;

    private static final long BASIS = 10_000;

    private GstRates() {
    }

    /** The rate for a line, from its category and its amount after discount. */
    static int rateBps(GstCategory category, long amountPaise, int quantity) {
        return switch (category) {
            case EXEMPT -> EXEMPT;
            case REDUCED -> MERIT;
            case STANDARD -> STANDARD;
            case DEMERIT -> DEMERIT;
            case APPAREL, FOOTWEAR -> amountPaise <= LOWER_SLAB_LIMIT_PER_PIECE_PAISE * quantity ? MERIT : STANDARD;
        };
    }

    /** The tax inside a GST-inclusive amount. Intra-state: one half, rounded, is both CGST and SGST. */
    static Tax extract(long amountPaise, int rateBps, TaxRegime regime) {
        if (rateBps == 0) {
            return Tax.NONE;
        }
        long scaled = Math.multiplyExact(amountPaise, rateBps);
        if (regime == TaxRegime.INTRA_STATE) {
            long half = halfUp(scaled, 2 * (BASIS + rateBps));
            return new Tax(half, half, 0);
        }
        return new Tax(0, 0, halfUp(scaled, BASIS + rateBps));
    }

    /** {@code numerator / denominator} rounded half up, for non-negative numerators. */
    static long halfUp(long numerator, long denominator) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(2, numerator), denominator), 2 * denominator);
    }
}
