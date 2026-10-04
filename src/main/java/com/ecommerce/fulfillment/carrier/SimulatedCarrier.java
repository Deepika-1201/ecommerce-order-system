package com.ecommerce.fulfillment.carrier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The V1 carrier, in process (ADR-025, LLD §8.9): bookings, cancellations and scans, with its parcels in
 * {@code fulfillment.simulated_parcels}. The delivery PIN code's last digit picks the scenario. Its scans enter the
 * inbox through {@link CarrierEvents}, as a verified webhook's would.
 */
@Component
public class SimulatedCarrier implements Carrier {

    static final String NAME = "Simulated Carrier";
    private static final int OUTAGE_TIMEOUTS = 2;

    /** What the carrier does with a parcel, by the last digit of its delivery PIN code. */
    public enum Scenario {
        STANDARD(List.of(Step.of("in_transit"), Step.of("out_for_delivery"), Step.of("delivered"))),
        OUTAGE(List.of(Step.of("in_transit"), Step.of("out_for_delivery"), Step.of("delivered"))),
        UNSERVICEABLE(List.of()),
        OUT_OF_ORDER(List.of(Step.of("delivered"), Step.lateBy("in_transit", Duration.ofSeconds(20)), Step.repeat(),
                Step.lateBy("out_for_delivery", Duration.ofSeconds(10)))),
        REATTEMPT(List.of(Step.of("in_transit"), Step.of("out_for_delivery"), Step.of("delivery_attempt_failed"),
                Step.of("out_for_delivery"), Step.of("delivered"))),
        RETURN(List.of(Step.of("in_transit"), Step.of("out_for_delivery"), Step.of("delivery_attempt_failed"),
                Step.of("out_for_delivery"), Step.of("delivery_attempt_failed"), Step.of("rto_in_transit"),
                Step.of("rto_delivered"))),
        REFUSED_ADDRESS(List.of());

        private final List<Step> itinerary;

        Scenario(List<Step> itinerary) {
            this.itinerary = itinerary;
        }

        public static Scenario of(String pinCode) {
            return switch (pinCode.charAt(pinCode.length() - 1)) {
                case '1' -> OUTAGE;
                case '2' -> UNSERVICEABLE;
                case '3' -> OUT_OF_ORDER;
                case '4' -> REATTEMPT;
                case '5' -> RETURN;
                case '6' -> REFUSED_ADDRESS;
                default -> STANDARD;
            };
        }

        public List<Step> itinerary() {
            return itinerary;
        }
    }

    /**
     * A scan of a scenario: sent as the parcel's newest, or late, stamped some time before the newest; or the previous
     * step's event delivered again.
     */
    public record Step(String scan, Duration late, boolean again) {

        static Step of(String scan) {
            return new Step(scan, Duration.ZERO, false);
        }

        static Step lateBy(String scan, Duration before) {
            return new Step(scan, before, false);
        }

        static Step repeat() {
            return new Step(null, Duration.ZERO, true);
        }
    }

    /** A parcel as the carrier keeps it: {@code scan} is its newest, where it is now. */
    public record SimulatedParcel(UUID reference, String awb, String pinCode, boolean cancelled, String scan, Instant scannedAt,
                         int nextStep) {

        public Scenario scenario() {
            return Scenario.of(pinCode);
        }
    }

    private final JdbcClient jdbc;
    private final CarrierEvents events;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SimulatedCarrier(JdbcClient jdbc, CarrierEvents events, JsonMapper json, TransactionTemplate transactions,
                     Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.json = json;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean serviceable(String pinCode) {
        return Scenario.of(pinCode) != Scenario.UNSERVICEABLE;
    }

    @Override
    public String book(Carrier.Parcel parcel) {
        String pinCode = parcel.deliveryAddress().pinCode();
        Scenario scenario = Scenario.of(pinCode);
        if (scenario == Scenario.UNSERVICEABLE) {
            throw new CarrierRefusedException("unserviceable", "The carrier does not deliver to " + pinCode);
        }
        if (scenario == Scenario.REFUSED_ADDRESS) {
            throw new CarrierRefusedException("invalid_address", "The carrier cannot deliver to this address");
        }
        Attempt attempt = transactions.execute(status -> jdbc.sql("""
                        INSERT INTO fulfillment.simulated_parcels (reference, pin_code, booking_attempts, created_at,
                                                                   updated_at)
                        VALUES (:reference, :pinCode, 1, :now, :now)
                        ON CONFLICT (reference) DO UPDATE
                        SET booking_attempts = simulated_parcels.booking_attempts + 1, updated_at = :now
                        RETURNING awb, booking_attempts
                        """)
                .param("reference", parcel.reference())
                .param("pinCode", pinCode)
                .param("now", now())
                .query((row, rowNumber) -> new Attempt(row.getString("awb"), row.getInt("booking_attempts")))
                .single());
        if (attempt.awb() != null) {
            return attempt.awb();
        }
        if (scenario == Scenario.OUTAGE && attempt.number() <= OUTAGE_TIMEOUTS) {
            throw new CarrierUnavailableException("The simulated carrier timed out, as its outage scenario does");
        }
        String awb = "SIM" + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
        jdbc.sql("UPDATE fulfillment.simulated_parcels SET awb = :awb, updated_at = :now WHERE reference = :reference")
                .param("awb", awb)
                .param("now", now())
                .param("reference", parcel.reference())
                .update();
        return awb;
    }

    @Override
    public void cancel(String awb) {
        transactions.executeWithoutResult(status -> {
            SimulatedParcel parcel = lockByAwb(awb)
                    .orElseThrow(() -> new CarrierRefusedException("unknown_awb", "No parcel " + awb));
            if (parcel.scan() != null) {
                throw new CarrierRefusedException("picked_up", "The carrier has parcel " + awb);
            }
            jdbc.sql("UPDATE fulfillment.simulated_parcels SET cancelled = true, updated_at = :now WHERE awb = :awb")
                    .param("now", now())
                    .param("awb", awb)
                    .update();
        });
    }

    public Optional<SimulatedParcel> parcel(UUID reference) {
        return jdbc.sql("SELECT * FROM fulfillment.simulated_parcels WHERE reference = :reference")
                .param("reference", reference)
                .query(SimulatedCarrier::simulatedParcel)
                .optional();
    }

    /**
     * The parcels the warehouse has handed over, not cancelled, whose scenario has scans left to send. The simulator
     * reads the handover from Fulfillment's shipments, as a carrier's driver would see the parcel (ADR-025).
     */
    public List<SimulatedParcel> handedOverParcelsWithScansLeft() {
        return jdbc.sql("""
                        SELECT p.* FROM fulfillment.simulated_parcels p
                        JOIN fulfillment.shipments s ON s.id = p.reference
                        WHERE p.awb IS NOT NULL AND NOT p.cancelled
                          AND s.status IN ('HANDED_OVER', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERY_ATTEMPT_FAILED',
                                           'DELIVERED', 'RTO_IN_TRANSIT', 'RTO_DELIVERED')
                        ORDER BY p.reference
                        """)
                .query(SimulatedCarrier::simulatedParcel)
                .list()
                .stream()
                .filter(parcel -> parcel.nextStep() < parcel.scenario().itinerary().size())
                .toList();
    }

    /** Sends the parcel's next scenario step, as its time comes in a local run. */
    public void advance(UUID reference) {
        transactions.executeWithoutResult(status -> {
            SimulatedParcel parcel = lock(reference).orElseThrow();
            List<Step> itinerary = parcel.scenario().itinerary();
            if (parcel.cancelled() || parcel.nextStep() >= itinerary.size()) {
                return;
            }
            int index = parcel.nextStep();
            Step step = itinerary.get(index);
            if (step.again()) {
                index--;
                step = itinerary.get(index);
            }
            String eventId = "evt_" + UUID.nameUUIDFromBytes((reference + ":" + index).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
            Instant occurredAt = step.late().isZero() ? newer(parcel) : parcel.scannedAt().minus(step.late());
            send(parcel, eventId, step.scan(), occurredAt);
            jdbc.sql("UPDATE fulfillment.simulated_parcels SET next_step = next_step + 1 WHERE reference = :reference")
                    .param("reference", reference)
                    .update();
        });
    }

    /** Sends a scan of the parcel now, after its newest. */
    public void scan(UUID reference, String scan) {
        transactions.executeWithoutResult(status -> {
            SimulatedParcel parcel = lock(reference).orElseThrow();
            send(parcel, newEventId(), scan, newer(parcel));
        });
    }

    /** Sends a scan of the parcel at the given time, whatever its others. */
    public void scan(UUID reference, String scan, Instant occurredAt) {
        transactions.executeWithoutResult(status -> send(lock(reference).orElseThrow(), newEventId(), scan, occurredAt));
    }

    private void send(SimulatedParcel parcel, String eventId, String scan, Instant occurredAt) {
        if (parcel.scannedAt() == null || occurredAt.isAfter(parcel.scannedAt())) {
            jdbc.sql("""
                            UPDATE fulfillment.simulated_parcels SET scan = :scan, scanned_at = :scannedAt, updated_at = :now
                            WHERE reference = :reference
                            """)
                    .param("scan", scan)
                    .param("scannedAt", occurredAt.atOffset(ZoneOffset.UTC))
                    .param("now", now())
                    .param("reference", parcel.reference())
                    .update();
        }
        events.receive(json.writeValueAsString(new EventJson(eventId, CarrierEvents.SCAN, clock.instant(),
                new ScanJson(parcel.reference(), parcel.awb(), scan, occurredAt, location(scan)))));
    }

    /** A second after the parcel's newest scan, or now if that is later: the carrier's clock only moves on. */
    private Instant newer(SimulatedParcel parcel) {
        Instant now = clock.instant();
        return parcel.scannedAt() == null || now.isAfter(parcel.scannedAt().plusSeconds(1)) ? now
                : parcel.scannedAt().plusSeconds(1);
    }

    private Optional<SimulatedParcel> lock(UUID reference) {
        return jdbc.sql("SELECT * FROM fulfillment.simulated_parcels WHERE reference = :reference FOR UPDATE")
                .param("reference", reference)
                .query(SimulatedCarrier::simulatedParcel)
                .optional();
    }

    private Optional<SimulatedParcel> lockByAwb(String awb) {
        return jdbc.sql("SELECT * FROM fulfillment.simulated_parcels WHERE awb = :awb FOR UPDATE")
                .param("awb", awb)
                .query(SimulatedCarrier::simulatedParcel)
                .optional();
    }

    private static String location(String scan) {
        return switch (scan) {
            case "picked_up", "rto_delivered" -> "Origin warehouse";
            case "in_transit", "rto_in_transit" -> "Sort centre";
            case "delivered" -> "Recipient's address";
            default -> "Last-mile hub";
        };
    }

    private static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static SimulatedParcel simulatedParcel(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime scannedAt = row.getObject("scanned_at", OffsetDateTime.class);
        return new SimulatedParcel(row.getObject("reference", UUID.class), row.getString("awb"), row.getString("pin_code"),
                row.getBoolean("cancelled"), row.getString("scan"), scannedAt == null ? null : scannedAt.toInstant(),
                row.getInt("next_step"));
    }

    private record Attempt(String awb, int number) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventJson(String id, String type, Instant createdAt, ScanJson data) {
    }

    record ScanJson(UUID reference, String awb, String scan, Instant occurredAt, String location) {
    }
}
