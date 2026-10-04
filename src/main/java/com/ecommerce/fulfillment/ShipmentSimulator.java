package com.ecommerce.fulfillment;

import java.time.Instant;
import java.util.UUID;

/**
 * Drives the warehouse and the carrier simulator in tests (LLD §8.9), with phase 6's methods, so that the saga's tests
 * did not change. The warehouse's marks take effect at once. Scans enter the inbox as the carrier's webhooks do, and
 * take effect when their task runs. A method the parcel's state does not allow throws {@link IllegalStateException}.
 */
public interface ShipmentSimulator {

    /** The warehouse packs the booked shipment and hands it over: {@code ShipmentHandedOver}. */
    void handOver(UUID orderId);

    /** The carrier scans the parcel in transit, out for delivery and delivered. */
    void deliver(UUID orderId);

    /** A delivery attempt fails, and the parcel starts back to the warehouse. */
    void startReturn(UUID orderId);

    /** The returning parcel is back at the warehouse. */
    void completeReturn(UUID orderId);

    /** Any scan of the order's parcel, by its carrier code, at a carrier time of the test's choosing. */
    void scan(UUID orderId, String scan, Instant occurredAt);

    /** Sends the rest of the parcel's scenario (LLD §8.9), as the simulator's clock would, one scan at a time. */
    void followScenario(UUID orderId);
}
