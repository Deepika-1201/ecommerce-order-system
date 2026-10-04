package com.ecommerce.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.customer.Customers;
import com.ecommerce.fulfillment.ShipmentMessages;
import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.Shipments;
import com.ecommerce.fulfillment.Shipments.TrackingEntry;
import com.ecommerce.fulfillment.carrier.Carrier;
import com.ecommerce.fulfillment.carrier.CarrierEvents;
import com.ecommerce.fulfillment.carrier.CarrierUnavailableException;
import com.ecommerce.fulfillment.carrier.SimulatedCarrier;
import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.WebhookInbox;
import com.ecommerce.platform.messaging.DueMessages;
import com.ecommerce.platform.tasks.DueTasks;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shipments through the saga's commands and the carrier simulator (LLD §8.5, §8.9): booking with retries and its budget,
 * refusals and support's re-drive, cancellation in every state, and the scenarios picked by PIN code.
 */
@ExtendWith(OutputCaptureExtension.class)
class ShipmentTests extends OrderingTest {

    private static final Class<?>[] ANSWERS = {ShipmentCancelled.class, ShipmentCancelRefused.class,
        ShipmentHandedOver.class, ShipmentDelivered.class, ShipmentReturnInitiated.class,
        ShipmentReturnedToOrigin.class};

    @Autowired
    private SimulatedCarrier carrier;
    @Autowired
    private Shipments queries;
    @Autowired
    private ShipmentRepository repository;
    @Autowired
    private ShipmentUpdates updates;
    @Autowired
    private CarrierEvents events;
    @Autowired
    private WebhookInbox inbox;
    @Autowired
    private Customers customers;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private Clock clock;

    private final UUID orderId = UUID.randomUUID();

    @Test
    void aShipmentIsBookedWithItsLinesUnderTheShipmentsReference() {
        command(create(orderId, "560038"));

        Map<String, Object> shipment = shipmentRow();
        assertThat(shipment.get("status")).isEqualTo("BOOKED");
        assertThat((String) shipment.get("awb")).matches("SIM[0-9]{10}");
        assertThat(shipment.get("carrier")).isEqualTo("Simulated Carrier");
        assertThat(repository.lines((UUID) shipment.get("id"))).containsExactly(
                new ShipmentMessages.Line("SKU-A", 2), new ShipmentMessages.Line("SKU-B", 1));
        assertThat(carrier.parcel((UUID) shipment.get("id")).orElseThrow().awb()).isEqualTo(shipment.get("awb"));
        assertThat(answers()).isEmpty();
    }

    @Test
    void aCarrierOutageIsRetriedUntilTheCarrierBooks() {
        command(create(orderId, "560001"));
        assertThat(shipmentRow().get("status")).isEqualTo("PENDING_BOOKING");
        assertThat(attempts()).isOne();

        retryNow();
        assertThat(shipmentRow().get("status")).as("still down").isEqualTo("PENDING_BOOKING");
        retryNow();

        assertThat(shipmentRow().get("status")).isEqualTo("BOOKED");
        assertThat(attempts()).isEqualTo(3);
        assertThat(carrier.parcel((UUID) shipmentRow().get("id")).orElseThrow().awb())
                .isEqualTo(shipmentRow().get("awb"));
    }

    @Test
    void aRefusedBookingFailsAndRaisesTheAlert(CapturedOutput output) {
        command(create(orderId, "560006"));
        UUID unserviceable = UUID.randomUUID();
        command(create(unserviceable, "560002"), unserviceable);

        assertThat(shipmentRow().get("status")).isEqualTo("BOOKING_FAILED");
        assertThat(shipmentRow().get("booking_failure")).isEqualTo("the carrier refused it: invalid_address");
        assertThat(column("SELECT booking_failure FROM fulfillment.shipments WHERE order_id = :id", unserviceable))
                .isEqualTo("the carrier refused it: unserviceable");
        assertThat(output.getOut()).contains("shipment_booking_failed: order " + orderId + ", shipment "
                + shipmentRow().get("id") + ": the carrier refused it: invalid_address");
        assertThat(attempts()).as("a refusal is not retried").isOne();
    }

    @Test
    void aBookingStopsOnceItsBudgetIsSpentAndSupportCanBookItAgain() {
        command(create(orderId, "560001"));
        jdbc.sql("UPDATE fulfillment.shipments SET booking_deadline = now() - interval '1 second' "
                        + "WHERE order_id = :id")
                .param("id", orderId)
                .update();
        retryNow();
        assertThat(shipmentRow().get("status")).isEqualTo("BOOKING_FAILED");
        assertThat(shipmentRow().get("booking_failure")).isEqualTo("its booking budget is spent");

        UUID shipmentId = (UUID) shipmentRow().get("id");
        assertThat(call("POST", "/v1/support/shipments/" + shipmentId + "/booking", support, null).statusCode())
                .isEqualTo(202);
        deliverExcept(ANSWERS);
        retryNow();

        assertThat(shipmentRow().get("status")).isEqualTo("BOOKED");
        assertThat(column("SELECT action FROM platform.audit_log WHERE target_type = 'shipment' AND target_id = :id",
                shipmentId.toString())).isEqualTo("fulfillment.shipment.booking-redriven");
        assertCode(call("POST", "/v1/support/shipments/" + shipmentId + "/booking", support, null), 409,
                "shipment_invalid_state");
        assertThat(call("POST", "/v1/support/shipments/" + shipmentId + "/booking", asha, null).statusCode())
                .isEqualTo(403);
        assertCode(call("POST", "/v1/support/shipments/" + UUID.randomUUID() + "/booking", support, null), 404,
                "not_found");
    }

    @Test
    void aCarrierDownForEveryAttemptFailsTheBookingOnTheLastOne(CapturedOutput output) {
        command(create(orderId, "560001"));
        ShipmentTasks tasks = new ShipmentTasks(repository, updates, carrier, events, inbox, customers, transactions,
                clock);

        tasks.bookShipment(new TaskExecution<>(UUID.randomUUID(), ShipmentTasks.BOOK,
                ShipmentTasks.BOOKING_ATTEMPTS, null, new ShipmentTasks.ShipmentTask(orderId, null)));

        assertThat(shipmentRow().get("status")).isEqualTo("BOOKING_FAILED");
        assertThat(shipmentRow().get("booking_failure"))
                .isEqualTo("the carrier stayed unavailable for " + ShipmentTasks.BOOKING_ATTEMPTS + " attempts");
        assertThat(output.getOut()).contains("shipment_booking_failed: order " + orderId);
    }

    @Test
    void aShipmentNotYetBookedIsCancelledAtOnceAndNeverBooked() {
        command(create(orderId, "560001"));

        command(new CancelShipment(orderId));
        retryNow();
        retryNow();

        assertThat(answers()).containsExactly("fulfillment.shipment-cancelled");
        assertThat(shipmentRow().get("status")).isEqualTo("CANCELLED");
        assertThat(carrier.parcel((UUID) shipmentRow().get("id")).orElseThrow().awb()).as("never booked").isNull();
    }

    @Test
    void aBookedOrPackedShipmentIsCancelledAtTheCarrier() {
        command(create(orderId, "560038"));
        UUID packed = UUID.randomUUID();
        command(create(packed, "560038"), packed);
        transactions.executeWithoutResult(status -> updates.mark(repository.lock(packed).orElseThrow(),
                ShipmentStatus.PACKED));

        command(new CancelShipment(orderId));
        command(new CancelShipment(packed), packed);
        command(new CancelShipment(orderId));

        assertThat(answers()).containsExactly("fulfillment.shipment-cancelled", "fulfillment.shipment-cancelled");
        assertThat(answers(packed)).containsExactly("fulfillment.shipment-cancelled");
        for (UUID order : List.of(orderId, packed)) {
            Map<String, Object> shipment = shipmentRow(order);
            assertThat(shipment.get("status")).isEqualTo("CANCELLED");
            assertThat(shipment.get("cancel_requested")).isEqualTo(false);
            assertThat(carrier.parcel((UUID) shipment.get("id")).orElseThrow().cancelled()).isTrue();
        }
    }

    @Test
    void aCancelTheCarrierRefusesBecauseItHasTheParcelIsRefused() {
        command(create(orderId, "560038"));
        publish(new CancelShipment(orderId), "order", orderId);
        DueMessages.deliverAllExcept(context, ANSWERS);
        assertThat(shipmentRow().get("cancel_requested")).isEqualTo(true);

        shipments.scan(orderId, "picked_up", Instant.now());
        DueTasks.runAll(context);

        assertThat(answers()).containsExactly("fulfillment.shipment-cancel-refused",
                "fulfillment.shipment-handed-over");
        assertThat(shipmentRow().get("status")).isEqualTo("HANDED_OVER");
        assertThat(shipmentRow().get("cancel_requested")).isEqualTo(false);
    }

    @Test
    void aScanThatArrivesWhileTheCancelWaitsWins() {
        command(create(orderId, "560038"));
        shipments.scan(orderId, "picked_up", Instant.now());
        publish(new CancelShipment(orderId), "order", orderId);

        deliverExcept(ANSWERS);

        assertThat(answers()).containsExactly("fulfillment.shipment-handed-over",
                "fulfillment.shipment-cancel-refused");
        assertThat(shipmentRow().get("status")).isEqualTo("HANDED_OVER");
    }

    @Test
    void aHandedOverShipmentRefusesItsCancellationAtOnce() {
        command(create(orderId, "560038"));
        shipments.handOver(orderId);

        command(new CancelShipment(orderId));

        assertThat(answers()).containsExactly("fulfillment.shipment-handed-over",
                "fulfillment.shipment-cancel-refused");
        assertThat(taskCount(ShipmentTasks.CANCEL)).isZero();
    }

    @Test
    void aBookingThatCompletesAfterItsCancellationIsCancelledAtTheCarrier() {
        command(create(orderId, "560001"));
        UUID shipmentId = (UUID) shipmentRow().get("id");
        Carrier cancelledMeanwhile = new Carrier() {
            @Override
            public String name() {
                return carrier.name();
            }

            @Override
            public boolean serviceable(String pinCode) {
                return true;
            }

            @Override
            public String book(Parcel parcel) {
                try {
                    carrier.book(parcel);
                } catch (CarrierUnavailableException stillDown) {
                    // The outage's second timeout; the third call books.
                }
                String awb = carrier.book(parcel);
                command(new CancelShipment(orderId));
                return awb;
            }

            @Override
            public void cancel(String awb) {
                carrier.cancel(awb);
            }
        };
        ShipmentTasks tasks = new ShipmentTasks(repository, updates, cancelledMeanwhile, events, inbox, customers,
                transactions, clock);

        tasks.bookShipment(new TaskExecution<>(UUID.randomUUID(), ShipmentTasks.BOOK, 2, null,
                new ShipmentTasks.ShipmentTask(orderId, null)));

        assertThat(shipmentRow().get("status")).isEqualTo("CANCELLED");
        assertThat(shipmentRow().get("awb")).as("never recorded as booked").isNull();
        assertThat(carrier.parcel(shipmentId).orElseThrow().cancelled()).isTrue();
        assertThat(answers()).containsExactly("fulfillment.shipment-cancelled");
    }

    @Test
    void aParcelDeliveredOutOfOrderIsDeliveredOnceWithTheLateScansKept() {
        handedOver("560003");

        shipments.followScenario(orderId);
        deliverExcept(ANSWERS);

        assertThat(shipmentRow().get("status")).isEqualTo("DELIVERED");
        assertThat(answers()).containsExactly("fulfillment.shipment-handed-over", "fulfillment.shipment-delivered");
        assertThat(tracking()).containsExactly("PACKED applied", "HANDED_OVER applied", "DELIVERED applied",
                "IN_TRANSIT late", "OUT_FOR_DELIVERY late");
        assertThat(queries.ofOrder(orderId).orElseThrow().tracking()).extracting(TrackingEntry::status)
                .as("what the order shows: the applied steps, newest first")
                .containsExactly(ShipmentStatus.DELIVERED, ShipmentStatus.HANDED_OVER, ShipmentStatus.PACKED);
    }

    @Test
    void theCarrierAnswersAKnownReferenceWithItsAwb() {
        command(create(orderId, "560038"));
        UUID shipmentId = (UUID) shipmentRow().get("id");
        Carrier.Parcel parcel = new Carrier.Parcel(shipmentId, new Carrier.Address("Asha Rao", "+919876543210",
                "12, 4th Cross", null, null, "Bengaluru", "29", "560038"), List.of());

        assertThat(carrier.book(parcel)).isEqualTo(shipmentRow().get("awb"));
        assertThat(carrier.parcel(shipmentId).orElseThrow().awb()).isEqualTo(shipmentRow().get("awb"));
    }

    @Test
    void aFailedAttemptIsDeliveredOnTheReattempt() {
        handedOver("560004");

        shipments.followScenario(orderId);
        deliverExcept(ANSWERS);

        assertThat(shipmentRow().get("status")).isEqualTo("DELIVERED");
        assertThat(answers()).containsExactly("fulfillment.shipment-handed-over", "fulfillment.shipment-delivered");
        assertThat(tracking()).containsExactly("PACKED applied", "HANDED_OVER applied", "IN_TRANSIT applied",
                "OUT_FOR_DELIVERY applied", "DELIVERY_ATTEMPT_FAILED applied", "OUT_FOR_DELIVERY applied",
                "DELIVERED applied");
    }

    @Test
    void aParcelThatCannotBeDeliveredReturnsToTheWarehouse() {
        handedOver("560005");

        shipments.followScenario(orderId);
        deliverExcept(ANSWERS);

        assertThat(shipmentRow().get("status")).isEqualTo("RTO_DELIVERED");
        assertThat(answers()).containsExactly("fulfillment.shipment-handed-over",
                "fulfillment.shipment-return-initiated", "fulfillment.shipment-returned-to-origin");
    }

    @Test
    void scansOfAnotherShipmentOrInAnotherCodeChangeNothing() {
        handedOver("560038");
        UUID shipmentId = (UUID) shipmentRow().get("id");

        events.receive(scanEvent("evt_unknown_reference", UUID.randomUUID(), "delivered"));
        events.receive(scanEvent("evt_unknown_scan", shipmentId, "lost_in_space"));
        events.receive(scanEvent("evt_no_time", shipmentId, "delivered").replace("\"occurred_at\"", "\"other\""));
        events.receive(scanEvent("evt_no_reference", shipmentId, "delivered").replace("\"reference\"", "\"other\""));
        events.receive(scanEvent("evt_no_scan", shipmentId, "delivered").replace("\"scan\"", "\"other\""));
        events.receive("""
                {"id": "evt_other_type", "type": "billing.invoice", "created_at": "2026-10-04T10:00:00Z", "data": {}}
                """);
        deliverExcept(ANSWERS);

        assertThat(shipmentRow().get("status")).isEqualTo("HANDED_OVER");
        assertThat(jdbc.sql("SELECT count(*) FROM platform.webhook_inbox WHERE processed_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(6);
    }

    private void handedOver(String pinCode) {
        command(create(orderId, pinCode));
        shipments.handOver(orderId);
    }

    private CreateShipment create(UUID order, String pinCode) {
        return new CreateShipment(order, deliverySnapshot(pinCode), List.of(new ShipmentMessages.Line("SKU-A", 2),
                new ShipmentMessages.Line("SKU-B", 1)));
    }

    private static String scanEvent(String eventId, UUID reference, String scan) {
        return """
                {"id": "%s", "type": "tracking.scan", "created_at": "2026-10-04T10:00:00Z",
                 "data": {"reference": "%s", "awb": "SIM0000000000", "scan": "%s",
                          "occurred_at": "2026-10-04T10:00:00Z", "location": "Nowhere"}}
                """.formatted(eventId, reference, scan);
    }

    private void command(Object command) {
        command(command, orderId);
    }

    private void command(Object command, UUID order) {
        publish(command, "order", order);
        deliverExcept(ANSWERS);
    }

    /** Makes the pending tasks due, as their backoff would, and runs them. */
    private void retryNow() {
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE status = 'PENDING'").update();
        deliverExcept(ANSWERS);
    }

    private int attempts() {
        return jdbc.sql("SELECT attempts FROM platform.scheduled_tasks WHERE dedupe_key = :key")
                .param("key", ShipmentTasks.BOOK + ":" + orderId)
                .query(Integer.class)
                .single();
    }

    private int taskCount(String type) {
        return jdbc.sql("SELECT count(*) FROM platform.scheduled_tasks WHERE type = :type")
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private Map<String, Object> shipmentRow() {
        return shipmentRow(orderId);
    }

    private Map<String, Object> shipmentRow(UUID order) {
        return jdbc.sql("SELECT * FROM fulfillment.shipments WHERE order_id = :id").param("id", order)
                .query().singleRow();
    }

    /** The shipment's tracking history as it arrived, each step marked applied or late. */
    private List<String> tracking() {
        return jdbc.sql("""
                        SELECT t.status || CASE WHEN t.applied THEN ' applied' ELSE ' late' END
                        FROM fulfillment.tracking_events t JOIN fulfillment.shipments s ON s.id = t.shipment_id
                        WHERE s.order_id = :id ORDER BY t.received_at, t.occurred_at
                        """)
                .param("id", orderId)
                .query(String.class)
                .list();
    }

    private List<String> answers() {
        return answers(orderId);
    }

    private List<String> answers(UUID order) {
        return jdbc.sql("""
                        SELECT type FROM platform.outbox
                        WHERE aggregate_id = :id AND aggregate_type = 'shipment' ORDER BY id
                        """)
                .param("id", order.toString())
                .query(String.class)
                .list();
    }

    private String column(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(String.class).single();
    }
}
