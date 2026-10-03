package com.ecommerce.fulfillment;

import java.util.UUID;

/**
 * Drives the simulated shipments that stand in for the warehouse and the carrier until phase 8 (ADR-022, LLD §6.8).
 * Each method runs in a transaction of its own and publishes the event the real module will. A method the shipment's
 * status does not allow throws {@link IllegalStateException}.
 */
public interface ShipmentSimulator {

    /** Booked to handed over: {@code ShipmentHandedOver}. */
    void handOver(UUID orderId);

    /** Handed over to delivered: {@code ShipmentDelivered}. */
    void deliver(UUID orderId);

    /** Handed over to returning, after a failed delivery: {@code ShipmentReturnInitiated}. */
    void startReturn(UUID orderId);

    /** Returning to back at the warehouse: {@code ShipmentReturnedToOrigin}. */
    void completeReturn(UUID orderId);
}
