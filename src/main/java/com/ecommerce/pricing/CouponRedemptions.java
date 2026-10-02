package com.ecommerce.pricing;

import java.util.UUID;

/**
 * One use of a coupon per order, held at placement and committed or released by the saga (LLD §4.10). Each
 * operation runs in its own transaction and can be repeated safely.
 */
public interface CouponRedemptions {

    /** Holds one use for the order. Repeating it returns the order's current outcome. */
    CouponReservation reserve(UUID orderId, UUID couponId, UUID customerId);

    /** {@code HELD} to {@code COMMITTED}; anything else is left alone. */
    void commit(UUID orderId);

    /** {@code HELD} or {@code COMMITTED} to {@code RELEASED}, giving the use back; anything else is left alone. */
    void release(UUID orderId);
}
