package com.ecommerce.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What Ordering reads from Fulfillment: an order's shipment as customers see it, and serviceability (LLD §8.10). */
public interface Shipments {

    /** The order's shipment with its applied tracking, newest first; empty until the saga asks for one. */
    Optional<ShipmentView> ofOrder(UUID orderId);

    /** Whether the carrier delivers to the PIN code, without calling it (ADR-026). */
    boolean serviceable(String pinCode);

    record ShipmentView(UUID id, ShipmentStatus status, String carrier, String awb, List<TrackingEntry> tracking) {

        public ShipmentView {
            tracking = List.copyOf(tracking);
        }
    }

    /** A step the parcel took, by the warehouse or the carrier, at its time. */
    record TrackingEntry(ShipmentStatus status, Instant at, String location) {
    }
}
