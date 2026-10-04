package com.ecommerce.fulfillment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A shipment's status, with the arrows of its state machine (order lifecycle §4). */
public enum ShipmentStatus {
    PENDING_BOOKING,
    BOOKING_FAILED,
    BOOKED,
    PACKED,
    HANDED_OVER,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERY_ATTEMPT_FAILED,
    DELIVERED,
    RTO_IN_TRANSIT,
    RTO_DELIVERED,
    CANCELLED;

    private static final Map<ShipmentStatus, Set<ShipmentStatus>> NEXT = Map.ofEntries(
            Map.entry(PENDING_BOOKING, EnumSet.of(BOOKED, BOOKING_FAILED, CANCELLED)),
            Map.entry(BOOKING_FAILED, EnumSet.of(PENDING_BOOKING, CANCELLED)),
            Map.entry(BOOKED, EnumSet.of(PACKED, CANCELLED)),
            Map.entry(PACKED, EnumSet.of(HANDED_OVER, CANCELLED)),
            Map.entry(HANDED_OVER, EnumSet.of(IN_TRANSIT)),
            Map.entry(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY)),
            Map.entry(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, DELIVERY_ATTEMPT_FAILED)),
            Map.entry(DELIVERY_ATTEMPT_FAILED, EnumSet.of(OUT_FOR_DELIVERY, RTO_IN_TRANSIT)),
            Map.entry(DELIVERED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(RTO_IN_TRANSIT, EnumSet.of(RTO_DELIVERED)),
            Map.entry(RTO_DELIVERED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(ShipmentStatus.class)));

    private static final Set<ShipmentStatus> HANDED_OVER_OR_LATER = EnumSet.of(HANDED_OVER, IN_TRANSIT,
            OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED, RTO_IN_TRANSIT, RTO_DELIVERED);

    /** The statuses one arrow away. */
    public Set<ShipmentStatus> next() {
        return NEXT.get(this);
    }

    /** Whether a path of at least one arrow leads to the status: itself only through the reattempt's cycle. */
    public boolean canReach(ShipmentStatus target) {
        Set<ShipmentStatus> seen = EnumSet.noneOf(ShipmentStatus.class);
        Deque<ShipmentStatus> pending = new ArrayDeque<>(next());
        while (!pending.isEmpty()) {
            ShipmentStatus status = pending.pop();
            if (status == target) {
                return true;
            }
            if (seen.add(status)) {
                pending.addAll(status.next());
            }
        }
        return false;
    }

    /** Whether the carrier has a booking for the parcel: booked and not cancelled. */
    public boolean isBooked() {
        return this == BOOKED || this == PACKED || HANDED_OVER_OR_LATER.contains(this);
    }

    /** Whether the parcel has left the warehouse. */
    public boolean isHandedOver() {
        return HANDED_OVER_OR_LATER.contains(this);
    }

    /** Whether the shipment has reached this status, or moved on from it along the delivery's arrows. */
    public boolean isAtOrPast(ShipmentStatus mark) {
        return this == mark || (this != CANCELLED && mark.canReach(this));
    }

    /** The milestones of this status, in the order they are reached (LLD §8.6). */
    public List<Milestone> milestones() {
        List<Milestone> reached = new ArrayList<>();
        if (isHandedOver()) {
            reached.add(Milestone.HANDED_OVER);
        }
        if (this == DELIVERED) {
            reached.add(Milestone.DELIVERED);
        }
        if (this == RTO_IN_TRANSIT || this == RTO_DELIVERED) {
            reached.add(Milestone.RETURN_INITIATED);
        }
        if (this == RTO_DELIVERED) {
            reached.add(Milestone.RETURNED_TO_ORIGIN);
        }
        return reached;
    }

    /** The status a carrier scan reports, by its code (LLD §8.6); empty for a code this system does not know. */
    public static Optional<ShipmentStatus> ofScan(String scan) {
        return Optional.ofNullable(switch (scan) {
            case "picked_up" -> HANDED_OVER;
            case "in_transit" -> IN_TRANSIT;
            case "out_for_delivery" -> OUT_FOR_DELIVERY;
            case "delivery_attempt_failed" -> DELIVERY_ATTEMPT_FAILED;
            case "delivered" -> DELIVERED;
            case "rto_in_transit" -> RTO_IN_TRANSIT;
            case "rto_delivered" -> RTO_DELIVERED;
            default -> null;
        });
    }

    /** The order-level facts a shipment's status implies, published once each when first reached. */
    public enum Milestone {
        HANDED_OVER,
        DELIVERED,
        RETURN_INITIATED,
        RETURNED_TO_ORIGIN
    }
}
