package com.ecommerce.inventory.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One change to a stock item's {@code on_hand} (ADR-021). {@code reason} and {@code note} explain adjustments,
 * {@code reference} receipts, and {@code orderId} handovers and returns; {@code actorId} is the staff member's subject,
 * absent for the saga.
 */
public record StockMovement(
        long id,
        String sku,
        String locationCode,
        MovementKind kind,
        int quantity,
        AdjustmentReason reason,
        String note,
        String reference,
        UUID orderId,
        long onHandAfter,
        String actorId,
        Instant createdAt) {
}
