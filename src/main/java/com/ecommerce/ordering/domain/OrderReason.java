package com.ecommerce.ordering.domain;

/** Why an order was rejected or cancelled (LLD §6.4). The others have no reason. */
public enum OrderReason {
    OUT_OF_STOCK,
    COUPON_UNAVAILABLE,
    PAYMENTS_UNAVAILABLE,
    CUSTOMER,
    SUPPORT,
    PAYMENT_FAILED,
    PAYMENT_EXPIRED,
    STOCK_LOST_AFTER_PAYMENT
}
