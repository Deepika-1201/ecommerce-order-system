package com.ecommerce.ordering.domain;

import com.ecommerce.inventory.StockReservations;
import java.util.List;
import java.util.UUID;

/** What the order process needs from the order's copies, which never change after placement. */
record OrderFacts(
        UUID orderId,
        UUID customerId,
        UUID couponId,
        long grandTotalPaise,
        List<StockReservations.Line> lines,
        UUID deliveryAddressId) {

    OrderFacts {
        lines = List.copyOf(lines);
    }
}
