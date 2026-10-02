package com.ecommerce.pricing.domain;

import java.time.Instant;
import java.util.UUID;

/** A coupon with its rule, window, limits and usage counters (LLD §4.9). */
public record Coupon(
        UUID id,
        String code,
        DiscountRule rule,
        long minOrderPaise,
        Instant validFrom,
        Instant validUntil,
        Integer totalLimit,
        Integer perCustomerLimit,
        boolean active,
        int reserved,
        int redeemed,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code valid_from} is inclusive, {@code valid_until} exclusive. */
    boolean notYetValid(Instant now) {
        return now.isBefore(validFrom);
    }

    boolean expired(Instant now) {
        return validUntil != null && !now.isBefore(validUntil);
    }

    boolean exhausted() {
        return totalLimit != null && reserved + redeemed >= totalLimit;
    }
}
