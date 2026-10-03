package com.ecommerce.ordering.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The status customers see (order lifecycle §2). Only the changes in its table are allowed. */
public enum OrderStatus {
    PLACED,
    AWAITING_PAYMENT,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNED_TO_ORIGIN,
    CANCELLING,
    CANCELLED,
    REJECTED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PLACED, EnumSet.of(AWAITING_PAYMENT, REJECTED, CANCELLING),
            AWAITING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED, CANCELLING),
            CONFIRMED, EnumSet.of(SHIPPED, CANCELLING),
            CANCELLING, EnumSet.of(CANCELLED, SHIPPED),
            SHIPPED, EnumSet.of(DELIVERED, DELIVERY_FAILED),
            DELIVERY_FAILED, EnumSet.of(RETURNED_TO_ORIGIN));

    public boolean canMoveTo(OrderStatus next) {
        return NEXT.getOrDefault(this, Set.of()).contains(next);
    }
}
