package com.ecommerce.inventory;

import java.time.Instant;
import java.util.UUID;

/** The outcome of reserving an order's stock. */
public sealed interface StockReservation {

    record Held(UUID reservationId, Instant expiresAt) implements StockReservation {
    }

    /** The first short line, in SKU order, and the units that were available for it. */
    record Rejected(String sku, long available) implements StockReservation {
    }
}
