package com.ecommerce.pricing;

/** The outcome of reserving a coupon use for an order. */
public sealed interface CouponReservation {

    record Held() implements CouponReservation {
    }

    record Unavailable(Reason reason) implements CouponReservation {
    }

    enum Reason {
        INACTIVE,
        OUTSIDE_WINDOW,
        EXHAUSTED,
        ALREADY_USED,
        /** This order's use was already given back. */
        RELEASED
    }
}
