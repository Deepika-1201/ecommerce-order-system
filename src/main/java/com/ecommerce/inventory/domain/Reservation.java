package com.ecommerce.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order's reservation; its lines in lock order. {@code shortSku} and {@code shortAvailable} only when rejected. */
record Reservation(
        UUID id,
        UUID orderId,
        ReservationStatus status,
        Instant expiresAt,
        String shortSku,
        Long shortAvailable,
        List<ReservedLine> lines) {
}
