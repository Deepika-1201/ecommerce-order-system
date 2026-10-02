package com.ecommerce.pricing.domain;

import java.time.Instant;

/** What an admin sends to create a coupon, or to change one; {@code null} means "not given" (LLD §4.9). */
public final class CouponCommands {

    private CouponCommands() {
    }

    public enum Kind {
        PERCENT,
        FLAT
    }

    public record NewCoupon(
            String code,
            Kind kind,
            Integer percentBps,
            Long maxDiscountPaise,
            Long amountPaise,
            Long minOrderPaise,
            Instant validFrom,
            Instant validUntil,
            Integer totalLimit,
            Integer perCustomerLimit,
            Boolean active) {
    }

    /** Only these can change: a different rule is a new code. */
    public record CouponChanges(Boolean active, Instant validUntil, Integer totalLimit) {
    }
}
