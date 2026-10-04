package com.ecommerce.fulfillment.domain;

import static com.ecommerce.fulfillment.ShipmentStatus.BOOKED;
import static com.ecommerce.fulfillment.ShipmentStatus.BOOKING_FAILED;
import static com.ecommerce.fulfillment.ShipmentStatus.CANCELLED;
import static com.ecommerce.fulfillment.ShipmentStatus.DELIVERED;
import static com.ecommerce.fulfillment.ShipmentStatus.DELIVERY_ATTEMPT_FAILED;
import static com.ecommerce.fulfillment.ShipmentStatus.HANDED_OVER;
import static com.ecommerce.fulfillment.ShipmentStatus.IN_TRANSIT;
import static com.ecommerce.fulfillment.ShipmentStatus.OUT_FOR_DELIVERY;
import static com.ecommerce.fulfillment.ShipmentStatus.PACKED;
import static com.ecommerce.fulfillment.ShipmentStatus.PENDING_BOOKING;
import static com.ecommerce.fulfillment.ShipmentStatus.RTO_DELIVERED;
import static com.ecommerce.fulfillment.ShipmentStatus.RTO_IN_TRANSIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.ShipmentStatus.Milestone;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The tracking rule of LLD §8.6 (ADR-024), cell by cell. */
class TrackingTests {

    private static final Instant LAST = Instant.parse("2026-10-04T10:00:00Z");
    private static final Set<ShipmentStatus> SCANNED = EnumSet.of(HANDED_OVER, IN_TRANSIT, OUT_FOR_DELIVERY,
            DELIVERY_ATTEMPT_FAILED, DELIVERED, RTO_IN_TRANSIT, RTO_DELIVERED);

    /** What a scan can move each status to, read off the state machine of order lifecycle §4 by hand. */
    private static final Map<ShipmentStatus, Set<ShipmentStatus>> REACHABLE = Map.ofEntries(
            Map.entry(PENDING_BOOKING, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(BOOKING_FAILED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(BOOKED, SCANNED),
            Map.entry(PACKED, SCANNED),
            Map.entry(HANDED_OVER, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED,
                    RTO_IN_TRANSIT, RTO_DELIVERED)),
            Map.entry(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED, RTO_IN_TRANSIT,
                    RTO_DELIVERED)),
            Map.entry(OUT_FOR_DELIVERY, EnumSet.of(OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED,
                    RTO_IN_TRANSIT, RTO_DELIVERED)),
            Map.entry(DELIVERY_ATTEMPT_FAILED, EnumSet.of(OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED,
                    RTO_IN_TRANSIT, RTO_DELIVERED)),
            Map.entry(DELIVERED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(RTO_IN_TRANSIT, EnumSet.of(RTO_DELIVERED)),
            Map.entry(RTO_DELIVERED, EnumSet.noneOf(ShipmentStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(ShipmentStatus.class)));

    @Test
    void aScanAppliesOnlyIfItIsLaterAndReachable() {
        for (ShipmentStatus current : ShipmentStatus.values()) {
            for (ShipmentStatus scanned : SCANNED) {
                boolean reachable = REACHABLE.get(current).contains(scanned);
                String cell = current + " scanned " + scanned;
                assertThat(Tracking.applies(current, LAST, scanned, LAST.plusSeconds(1))).as(cell + ", later")
                        .isEqualTo(reachable);
                assertThat(Tracking.applies(current, null, scanned, LAST)).as(cell + ", first scan")
                        .isEqualTo(reachable);
                assertThat(Tracking.applies(current, LAST, scanned, LAST)).as(cell + ", same time").isFalse();
                assertThat(Tracking.applies(current, LAST, scanned, LAST.minusSeconds(1))).as(cell + ", earlier")
                        .isFalse();
            }
        }
    }

    @Test
    void aJumpPublishesEveryMilestoneItReachesInOrder() {
        assertThat(Tracking.reached(PACKED, HANDED_OVER)).containsExactly(Milestone.HANDED_OVER);
        assertThat(Tracking.reached(BOOKED, DELIVERED)).containsExactly(Milestone.HANDED_OVER, Milestone.DELIVERED);
        assertThat(Tracking.reached(PACKED, RTO_DELIVERED)).containsExactly(Milestone.HANDED_OVER,
                Milestone.RETURN_INITIATED, Milestone.RETURNED_TO_ORIGIN);
        assertThat(Tracking.reached(OUT_FOR_DELIVERY, RTO_DELIVERED)).containsExactly(Milestone.RETURN_INITIATED,
                Milestone.RETURNED_TO_ORIGIN);
        assertThat(Tracking.reached(RTO_IN_TRANSIT, RTO_DELIVERED)).containsExactly(Milestone.RETURNED_TO_ORIGIN);
        assertThat(Tracking.reached(IN_TRANSIT, DELIVERED)).containsExactly(Milestone.DELIVERED);
        for (ShipmentStatus within : List.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED)) {
            assertThat(Tracking.reached(HANDED_OVER, within)).as(within.name()).isEmpty();
            assertThat(Tracking.reached(within, OUT_FOR_DELIVERY)).as(within.name()).isEmpty();
        }
        assertThat(Tracking.reached(BOOKED, PACKED)).isEmpty();
    }

    @Test
    void theCarriersScanCodesNameTheStatuses() {
        assertThat(ShipmentStatus.ofScan("picked_up")).contains(HANDED_OVER);
        assertThat(ShipmentStatus.ofScan("in_transit")).contains(IN_TRANSIT);
        assertThat(ShipmentStatus.ofScan("out_for_delivery")).contains(OUT_FOR_DELIVERY);
        assertThat(ShipmentStatus.ofScan("delivery_attempt_failed")).contains(DELIVERY_ATTEMPT_FAILED);
        assertThat(ShipmentStatus.ofScan("delivered")).contains(DELIVERED);
        assertThat(ShipmentStatus.ofScan("rto_in_transit")).contains(RTO_IN_TRANSIT);
        assertThat(ShipmentStatus.ofScan("rto_delivered")).contains(RTO_DELIVERED);
        assertThat(ShipmentStatus.ofScan("lost")).isEqualTo(Optional.empty());
        assertThat(ShipmentStatus.ofScan("PICKED_UP")).isEmpty();
    }

    @Test
    void aWarehouseMarkIsPassedOnlyAlongTheDeliverysArrows() {
        assertThat(EnumSet.allOf(ShipmentStatus.class).stream().filter(status -> status.isAtOrPast(PACKED)))
                .containsExactlyInAnyOrder(PACKED, HANDED_OVER, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED,
                        DELIVERED, RTO_IN_TRANSIT, RTO_DELIVERED);
        assertThat(EnumSet.allOf(ShipmentStatus.class).stream().filter(status -> status.isAtOrPast(HANDED_OVER)))
                .containsExactlyInAnyOrder(HANDED_OVER, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED,
                        DELIVERED, RTO_IN_TRANSIT, RTO_DELIVERED);
    }
}
