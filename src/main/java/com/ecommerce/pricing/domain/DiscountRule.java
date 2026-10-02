package com.ecommerce.pricing.domain;

/** A coupon's discount rule, fixed when the coupon is created (LLD §4.9). */
public sealed interface DiscountRule {

    /** The discount on a gross subtotal, before the "never below ₹1" floor. */
    long discountOn(long grossPaise);

    /** Basis points of the gross subtotal, rounded down, then capped. */
    record Percent(int basisPoints, Long capPaise) implements DiscountRule {

        @Override
        public long discountOn(long grossPaise) {
            long discount = Math.multiplyExact(grossPaise, basisPoints) / 10_000;
            return capPaise == null ? discount : Math.min(discount, capPaise);
        }
    }

    record Flat(long amountPaise) implements DiscountRule {

        @Override
        public long discountOn(long grossPaise) {
            return amountPaise;
        }
    }
}
