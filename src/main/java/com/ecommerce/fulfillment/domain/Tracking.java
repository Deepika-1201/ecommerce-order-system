package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.ShipmentStatus.Milestone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The tracking rule of LLD §8.6 (ADR-024), without I/O. */
final class Tracking {

    private Tracking() {
    }

    /**
     * Whether a scan applies: the carrier has the shipment's booking, the scan is newer than the last applied one, and
     * its status is reachable from the current one.
     */
    static boolean applies(ShipmentStatus current, Instant lastScanAt, ShipmentStatus scanned, Instant occurredAt) {
        return current.isBooked()
                && (lastScanAt == null || occurredAt.isAfter(lastScanAt))
                && current.canReach(scanned);
    }

    /** The milestones a change from one status to another reaches, in order. */
    static List<Milestone> reached(ShipmentStatus before, ShipmentStatus after) {
        List<Milestone> reached = new ArrayList<>(after.milestones());
        reached.removeAll(before.milestones());
        return reached;
    }
}
