package com.ecommerce.pricing;

import com.ecommerce.platform.MessageType;
import java.util.UUID;

/**
 * The order saga's commands to Pricing and Pricing's replies (LLD §6.2). Replies come from aggregate
 * {@code coupon_redemption}, keyed by the order id, with sequence 0: redemptions have no version.
 */
public final class CouponMessages {

    private CouponMessages() {
    }

    /** Hold one use of the coupon for the order: {@link CouponReserved} or {@link CouponUnavailable}. */
    @MessageType(name = "pricing.reserve-coupon")
    public record ReserveCoupon(UUID orderId, UUID couponId, UUID customerId) {
    }

    @MessageType(name = "pricing.coupon-reserved")
    public record CouponReserved(UUID orderId) {
    }

    @MessageType(name = "pricing.coupon-unavailable")
    public record CouponUnavailable(UUID orderId, CouponReservation.Reason reason) {
    }

    /** Payment succeeded and the stock is committed: the held use counts as redeemed. No reply. */
    @MessageType(name = "pricing.commit-coupon")
    public record CommitCoupon(UUID orderId) {
    }

    /** Give back the order's use, held or committed. No reply. */
    @MessageType(name = "pricing.release-coupon")
    public record ReleaseCoupon(UUID orderId) {
    }
}
