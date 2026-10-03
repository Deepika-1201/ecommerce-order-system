package com.ecommerce.cart.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A cart and its lines, in the order they were added. {@code customerId} is {@code null} for a guest cart. */
public record Cart(UUID id, UUID customerId, String couponCode, long version, Instant expiresAt, List<Line> lines) {

    public Cart {
        lines = List.copyOf(lines);
    }

    /** {@code addedPricePaise} is the list price when the line was first added. */
    public record Line(String sku, int quantity, long addedPricePaise, Instant addedAt) {
    }
}
