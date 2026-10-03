package com.ecommerce.ordering.domain;

import java.time.Instant;
import java.util.UUID;

/** An order in a customer's list: enough to recognise it, without its lines or addresses. */
public record OrderSummary(
        UUID id,
        String number,
        OrderStatus status,
        OrderReason reason,
        int itemCount,
        long grandTotalPaise,
        Instant placedAt) {
}
