package com.ecommerce.inventory.domain;

import java.time.Instant;

/** A stock item's counters; {@code version} increases with every change (LLD §5.3). */
public record StockLevel(String sku, String locationCode, long onHand, long reserved, long version, Instant updatedAt) {

    public long available() {
        return onHand - reserved;
    }
}
