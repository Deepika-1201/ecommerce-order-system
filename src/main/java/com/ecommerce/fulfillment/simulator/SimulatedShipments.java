package com.ecommerce.fulfillment.simulator;

import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.fulfillment.ShipmentSimulator;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fulfillment as a simulator until phase 8 (ADR-022, LLD §6.8): a shipment per order, booked at once, cancellable
 * until it is handed over. Tests move it on through {@link ShipmentSimulator}.
 */
@Component
class SimulatedShipments implements ShipmentSimulator {

    static final String AGGREGATE = "shipment";

    enum Status {
        BOOKED,
        HANDED_OVER,
        DELIVERED,
        RETURNING,
        RETURNED,
        CANCELLED
    }

    private record Shipment(UUID orderId, Status status, long version) {
    }

    private final JdbcClient jdbc;
    private final Messages messages;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SimulatedShipments(JdbcClient jdbc, Messages messages, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Booked at once; a shipment cancelled before this arrived stays cancelled. */
    @HandlesMessage(consumer = "fulfillment.create-shipment")
    void createShipment(IncomingMessage<CreateShipment> message) {
        insertIfAbsent(message.payload().orderId(), Status.BOOKED);
    }

    /** Cancelled before the handover, even before the create arrives; refused after. */
    @HandlesMessage(consumer = "fulfillment.cancel-shipment")
    void cancelShipment(IncomingMessage<CancelShipment> message) {
        UUID orderId = message.payload().orderId();
        insertIfAbsent(orderId, Status.CANCELLED);
        Shipment shipment = lock(orderId);
        if (shipment.status() == Status.BOOKED) {
            shipment = setStatus(orderId, Status.CANCELLED);
        }
        messages.publish(shipment.status() == Status.CANCELLED
                        ? new ShipmentCancelled(orderId)
                        : new ShipmentCancelRefused(orderId),
                origin(shipment), Correlation.causedBy(message));
    }

    @Override
    public void handOver(UUID orderId) {
        change(orderId, Status.BOOKED, Status.HANDED_OVER, ShipmentHandedOver::new);
    }

    @Override
    public void deliver(UUID orderId) {
        change(orderId, Status.HANDED_OVER, Status.DELIVERED, ShipmentDelivered::new);
    }

    @Override
    public void startReturn(UUID orderId) {
        change(orderId, Status.HANDED_OVER, Status.RETURNING, ShipmentReturnInitiated::new);
    }

    @Override
    public void completeReturn(UUID orderId) {
        change(orderId, Status.RETURNING, Status.RETURNED, ShipmentReturnedToOrigin::new);
    }

    private void change(UUID orderId, Status from, Status to, Function<UUID, Object> event) {
        transactions.executeWithoutResult(transaction -> {
            Shipment shipment = lock(orderId);
            if (shipment.status() != from) {
                throw new IllegalStateException("The " + shipment.status() + " shipment of order " + orderId
                        + " cannot become " + to);
            }
            messages.publish(event.apply(orderId), origin(setStatus(orderId, to)),
                    Correlation.start(orderId.toString()));
        });
    }

    private void insertIfAbsent(UUID orderId, Status status) {
        jdbc.sql("""
                        INSERT INTO fulfillment.simulated_shipments (order_id, status, version, created_at, updated_at)
                        VALUES (:orderId, :status, 1, :now, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("orderId", orderId)
                .param("status", status.name())
                .param("now", now())
                .update();
    }

    private Shipment lock(UUID orderId) {
        return jdbc.sql("SELECT order_id, status, version FROM fulfillment.simulated_shipments "
                        + "WHERE order_id = :orderId FOR UPDATE")
                .param("orderId", orderId)
                .query((row, rowNumber) -> new Shipment(row.getObject("order_id", UUID.class),
                        Status.valueOf(row.getString("status")), row.getLong("version")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no shipment"));
    }

    private Shipment setStatus(UUID orderId, Status status) {
        long version = jdbc.sql("""
                        UPDATE fulfillment.simulated_shipments
                        SET status = :status, version = version + 1, updated_at = :now
                        WHERE order_id = :orderId
                        RETURNING version
                        """)
                .param("status", status.name())
                .param("now", now())
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        return new Shipment(orderId, status, version);
    }

    private static Origin origin(Shipment shipment) {
        return new Origin(AGGREGATE, shipment.orderId(), shipment.version());
    }

    private OffsetDateTime now() {
        Instant now = clock.instant();
        return now.atOffset(ZoneOffset.UTC);
    }
}
