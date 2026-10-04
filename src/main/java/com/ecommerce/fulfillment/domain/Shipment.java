package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.ShipmentStatus;
import java.time.Instant;
import java.util.UUID;

/** A shipment as Fulfillment keeps it (LLD §8.4). */
record Shipment(
        UUID id,
        UUID orderId,
        UUID deliveryAddressId,
        ShipmentStatus status,
        String carrier,
        String awb,
        Instant bookingDeadline,
        String bookingFailure,
        Instant lastScanAt,
        boolean cancelRequested,
        long version) {
}
