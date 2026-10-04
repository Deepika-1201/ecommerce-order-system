package com.ecommerce.fulfillment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.ordering.OrderingTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The fulfillment schema refuses what no update produces (LLD §8.12), check by check: an AWB before the booking or
 * none after it, a carrier without an AWB, a pending booking without its deadline, a failure without its reason, a
 * cancel requested outside the booked statuses, an address missing, quantities of nothing, and tracking rows whose
 * source does not fit.
 */
class FulfillmentSchemaTests extends OrderingTest {

    @Test
    void anAwbAndItsCarrierExactlyFromTheBookingOn() {
        UUID pending = shipment("560001");
        UUID booked = shipment("560038");

        assertRejected("shipments_awb", "UPDATE fulfillment.shipments SET awb = 'SIM1', carrier = 'c' "
                + "WHERE order_id = :id", pending);
        assertRejected("shipments_awb", "UPDATE fulfillment.shipments SET status = 'BOOKING_FAILED', "
                + "booking_failure = 'x', awb = 'SIM2', carrier = 'c' WHERE order_id = :id", pending);
        assertRejected("shipments_awb", "UPDATE fulfillment.shipments SET awb = NULL, carrier = NULL "
                + "WHERE order_id = :id", booked);
        assertRejected("shipments_carrier", "UPDATE fulfillment.shipments SET carrier = NULL WHERE order_id = :id",
                booked);
    }

    @Test
    void aPendingBookingHasItsDeadlineAndAFailedOneItsReason() {
        UUID pending = shipment("560001");
        UUID booked = shipment("560038");

        assertRejected("shipments_booking_deadline", "UPDATE fulfillment.shipments SET booking_deadline = NULL "
                + "WHERE order_id = :id", pending);
        assertRejected("shipments_booking_failure", "UPDATE fulfillment.shipments SET status = 'BOOKING_FAILED' "
                + "WHERE order_id = :id", pending);
        assertRejected("shipments_booking_failure", "UPDATE fulfillment.shipments SET booking_failure = 'x' "
                + "WHERE order_id = :id", booked);
    }

    @Test
    void aCancelIsRequestedOnlyOfABookedOrPackedShipment() {
        UUID pending = shipment("560001");

        assertRejected("shipments_cancel_requested", "UPDATE fulfillment.shipments SET cancel_requested = true "
                + "WHERE order_id = :id", pending);
    }

    @Test
    void onlyACancelledShipmentLacksAnAddressAndEveryLineHasUnits() {
        UUID booked = shipment("560038");

        assertRejected("shipments_address", "UPDATE fulfillment.shipments SET delivery_address_id = NULL "
                + "WHERE order_id = :id", booked);
        assertRejected("shipment_lines_quantity_check", "UPDATE fulfillment.shipment_lines SET quantity = 0 "
                + "WHERE shipment_id = (SELECT id FROM fulfillment.shipments WHERE order_id = :id)", booked);
    }

    @Test
    void aCarrierScanHasItsEventIdAndAWarehouseMarkNone() {
        UUID booked = shipment("560038");
        String insert = """
                INSERT INTO fulfillment.tracking_events (id, shipment_id, source, event_id, status, occurred_at,
                                                         received_at, applied)
                SELECT gen_random_uuid(), id, %s, now(), now(), %s FROM fulfillment.shipments WHERE order_id = :id
                """;

        assertRejected("tracking_events_source", insert.formatted("'CARRIER', NULL, 'IN_TRANSIT'", "true"), booked);
        assertRejected("tracking_events_source", insert.formatted("'WAREHOUSE', 'evt_1', 'PACKED'", "true"), booked);
        assertRejected("tracking_events_source", insert.formatted("'WAREHOUSE', NULL, 'IN_TRANSIT'", "true"), booked);
        assertRejected("tracking_events_source", insert.formatted("'WAREHOUSE', NULL, 'PACKED'", "false"), booked);
    }

    /** A shipment of order with a delivery address at this PIN code, after its booking task ran; the order's id. */
    private UUID shipment(String pinCode) {
        UUID order = UUID.randomUUID();
        publish(new CreateShipment(order, deliverySnapshot(pinCode), List.of(new ShipmentMessages.Line("SKU-A", 1))),
                "order", order);
        deliver();
        return order;
    }

    private void assertRejected(String constraint, String sql, UUID orderId) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", orderId).update())
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining('"' + constraint + '"');
    }
}
