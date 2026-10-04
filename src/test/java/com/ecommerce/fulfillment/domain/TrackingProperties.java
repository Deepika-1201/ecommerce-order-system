package com.ecommerce.fulfillment.domain;

import static com.ecommerce.fulfillment.ShipmentStatus.DELIVERED;
import static com.ecommerce.fulfillment.ShipmentStatus.DELIVERY_ATTEMPT_FAILED;
import static com.ecommerce.fulfillment.ShipmentStatus.HANDED_OVER;
import static com.ecommerce.fulfillment.ShipmentStatus.IN_TRANSIT;
import static com.ecommerce.fulfillment.ShipmentStatus.OUT_FOR_DELIVERY;
import static com.ecommerce.fulfillment.ShipmentStatus.RTO_DELIVERED;
import static com.ecommerce.fulfillment.ShipmentStatus.RTO_IN_TRANSIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.ShipmentStatus.Milestone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The phase 8 exit criterion: tracking never moves backwards (FR-FUL3). A parcel's true journey after the handover,
 * its scans delivered in any order and some of them twice, always leaves the shipment where the newest scan says, having
 * moved only forward along the journey and published each milestone once, in order (ADR-024).
 */
class TrackingProperties {

    private static final Instant HANDOVER = Instant.parse("2026-10-04T10:00:00Z");

    /** A scan of the journey: its place in it, its status, and the carrier's time, a minute after the one before. */
    record Scan(int step, ShipmentStatus status, Instant at) {
    }

    @Property
    void theShipmentEndsWhereTheNewestScanSaysHavingOnlyMovedForward(@ForAll("arrivals") List<Scan> arrivals) {
        ShipmentStatus status = HANDED_OVER;
        Instant lastScanAt = null;
        int lastStep = -1;
        List<Milestone> published = new ArrayList<>();
        for (Scan scan : arrivals) {
            if (Tracking.applies(status, lastScanAt, scan.status(), scan.at())) {
                assertThat(scan.step()).as("a later step of the journey than the last applied").isGreaterThan(lastStep);
                published.addAll(Tracking.reached(status, scan.status()));
                status = scan.status();
                lastScanAt = scan.at();
                lastStep = scan.step();
            }
        }

        Scan newest = arrivals.stream().max(Comparator.comparing(Scan::at)).orElseThrow();
        assertThat(status).isEqualTo(newest.status());
        assertThat(lastScanAt).isEqualTo(newest.at());
        List<Milestone> expected = new ArrayList<>(newest.status().milestones());
        expected.remove(Milestone.HANDED_OVER);
        assertThat(published).as("each milestone once, in order").containsExactlyElementsOf(expected);
    }

    @Provide
    Arbitrary<List<Scan>> arrivals() {
        Arbitrary<List<Scan>> journeys = Combinators.combine(Arbitraries.integers().between(0, 3),
                Arbitraries.of(true, false), Arbitraries.integers().between(1, 12)).as(TrackingProperties::journey);
        return journeys.flatMap(journey -> Arbitraries.integers().between(0, journey.size() - 1).list().ofMaxSize(3)
                .flatMap(repeated -> {
                    List<Scan> arriving = new ArrayList<>(journey);
                    repeated.forEach(step -> arriving.add(journey.get(step)));
                    return Arbitraries.shuffle(arriving);
                }));
    }

    /**
     * In transit and out for delivery, then each failed attempt, until delivered or, after a failure, back to the
     * warehouse; only its first {@code scans} scans have happened yet.
     */
    static List<Scan> journey(int failedAttempts, boolean returned, int scans) {
        List<ShipmentStatus> statuses = new ArrayList<>(List.of(IN_TRANSIT, OUT_FOR_DELIVERY));
        for (int attempt = 1; attempt <= failedAttempts; attempt++) {
            statuses.add(DELIVERY_ATTEMPT_FAILED);
            if (attempt < failedAttempts || !returned) {
                statuses.add(OUT_FOR_DELIVERY);
            }
        }
        statuses.addAll(returned && failedAttempts > 0 ? List.of(RTO_IN_TRANSIT, RTO_DELIVERED) : List.of(DELIVERED));
        List<Scan> journey = new ArrayList<>();
        for (int step = 0; step < Math.min(scans, statuses.size()); step++) {
            journey.add(new Scan(step, statuses.get(step), HANDOVER.plusSeconds(60L * (step + 1))));
        }
        return journey;
    }
}
