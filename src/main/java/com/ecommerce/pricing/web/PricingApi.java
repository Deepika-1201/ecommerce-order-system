package com.ecommerce.pricing.web;

import com.ecommerce.pricing.domain.Coupon;
import com.ecommerce.pricing.domain.CouponCommands.Kind;
import com.ecommerce.pricing.domain.DiscountRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of coupon administration (LLD §4.9). */
final class PricingApi {

    private PricingApi() {
    }

    record CreateCoupon(
            @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{3,19}",
                    message = "must be 4 to 20 letters, digits and hyphens") String code,
            @NotNull Kind kind,
            @Min(1) @Max(10_000) Integer percentBps,
            @Min(1) @Max(1_000_000_000) Long maxDiscountPaise,
            @Min(1) @Max(1_000_000_000) Long amountPaise,
            @Min(0) @Max(1_000_000_000) Long minOrderPaise,
            Instant validFrom,
            Instant validUntil,
            @Min(1) Integer totalLimit,
            @Min(1) Integer perCustomerLimit,
            Boolean active) {
    }

    /** Fields left out are unchanged. {@code valid_until} can be moved but not removed. */
    record UpdateCoupon(Boolean active, Instant validUntil, @Min(1) Integer totalLimit) {
    }

    record CouponResponse(
            UUID id,
            String code,
            Kind kind,
            Integer percentBps,
            Long maxDiscountPaise,
            Long amountPaise,
            long minOrderPaise,
            Instant validFrom,
            Instant validUntil,
            Integer totalLimit,
            Integer perCustomerLimit,
            boolean active,
            int reserved,
            int redeemed,
            Instant createdAt,
            Instant updatedAt) {

        static CouponResponse of(Coupon coupon) {
            return switch (coupon.rule()) {
                case DiscountRule.Percent percent -> of(coupon, Kind.PERCENT, percent.basisPoints(),
                        percent.capPaise(), null);
                case DiscountRule.Flat flat -> of(coupon, Kind.FLAT, null, null, flat.amountPaise());
            };
        }

        private static CouponResponse of(Coupon coupon, Kind kind, Integer percentBps, Long cap, Long amount) {
            return new CouponResponse(coupon.id(), coupon.code(), kind, percentBps, cap, amount,
                    coupon.minOrderPaise(), coupon.validFrom(), coupon.validUntil(), coupon.totalLimit(),
                    coupon.perCustomerLimit(), coupon.active(), coupon.reserved(), coupon.redeemed(),
                    coupon.createdAt(), coupon.updatedAt());
        }
    }

    record CouponList(List<CouponResponse> items, String nextCursor) {
    }
}
