package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.Line;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.Shipments.TrackingEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for shipments, their lines and their tracking (LLD §8.4). Every change counts in the shipment's {@code version},
 * the sequence of what Fulfillment publishes.
 */
@Repository
class ShipmentRepository {

    private static final String COLUMNS = """
            id, order_id, delivery_address_id, status, carrier, awb, booking_deadline, booking_failure, last_scan_at,
            cancel_requested, version""";

    private final JdbcClient jdbc;

    ShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the order's shipment, pending its booking, with its lines, unless the order has one. */
    void insertIfAbsent(UUID id, CreateShipment command, Instant bookingDeadline, Instant now) {
        boolean inserted = jdbc.sql("""
                        INSERT INTO fulfillment.shipments (id, order_id, delivery_address_id, status, booking_deadline,
                                                           version, created_at, updated_at)
                        VALUES (:id, :orderId, :addressId, 'PENDING_BOOKING', :deadline, 1, :now, :now)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", id)
                .param("orderId", command.orderId())
                .param("addressId", command.deliveryAddressId())
                .param("deadline", utc(bookingDeadline))
                .param("now", utc(now))
                .update() == 1;
        if (inserted) {
            for (Line line : command.lines()) {
                jdbc.sql("INSERT INTO fulfillment.shipment_lines (shipment_id, sku, quantity) "
                                + "VALUES (:id, :sku, :quantity)")
                        .param("id", id)
                        .param("sku", line.sku())
                        .param("quantity", line.quantity())
                        .update();
            }
        }
    }

    /** Records the order's shipment as cancelled before its create arrived, unless the order has one. */
    void insertCancelled(UUID id, UUID orderId, Instant now) {
        jdbc.sql("""
                        INSERT INTO fulfillment.shipments (id, order_id, status, version, created_at, updated_at)
                        VALUES (:id, :orderId, 'CANCELLED', 1, :now, :now)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("now", utc(now))
                .update();
    }

    Optional<Shipment> find(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fulfillment.shipments WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(ShipmentRepository::shipment)
                .optional();
    }

    /** Locks the order's shipment until the transaction ends. */
    Optional<Shipment> lock(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fulfillment.shipments WHERE order_id = :orderId FOR UPDATE")
                .param("orderId", orderId)
                .query(ShipmentRepository::shipment)
                .optional();
    }

    Optional<Shipment> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fulfillment.shipments WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(ShipmentRepository::shipment)
                .optional();
    }

    Shipment booked(UUID id, String carrier, String awb, Instant now) {
        return update("status = 'BOOKED', carrier = :carrier, awb = :awb", id, now)
                .param("carrier", carrier)
                .param("awb", awb)
                .query(ShipmentRepository::shipment)
                .single();
    }

    Shipment bookingFailed(UUID id, String reason, Instant now) {
        return update("status = 'BOOKING_FAILED', booking_failure = :reason", id, now)
                .param("reason", reason)
                .query(ShipmentRepository::shipment)
                .single();
    }

    /** Support's re-drive: pending its booking again, with a new deadline. */
    Shipment rebook(UUID id, Instant bookingDeadline, Instant now) {
        return update("status = 'PENDING_BOOKING', booking_failure = NULL, booking_deadline = :deadline", id, now)
                .param("deadline", utc(bookingDeadline))
                .query(ShipmentRepository::shipment)
                .single();
    }

    Shipment requestCancel(UUID id, Instant now) {
        return update("cancel_requested = true", id, now).query(ShipmentRepository::shipment).single();
    }

    Shipment cancelled(UUID id, Instant now) {
        return update("status = 'CANCELLED', cancel_requested = false, booking_failure = NULL", id, now)
                .query(ShipmentRepository::shipment)
                .single();
    }

    /** The carrier has the parcel: the cancel is refused, and the shipment waits for its scans. */
    Shipment cancelRefused(UUID id, Instant now) {
        return update("cancel_requested = false", id, now).query(ShipmentRepository::shipment).single();
    }

    /**
     * Moves the shipment along its state machine: by a carrier scan, which also sets the last scan's time, or by a
     * warehouse mark, which does not. A cancel requested no longer stands once the parcel has left.
     */
    Shipment moved(UUID id, ShipmentStatus status, Instant scannedAt, Instant now) {
        return update("""
                        status = :status, last_scan_at = coalesce(CAST(:scannedAt AS timestamptz), last_scan_at),
                        cancel_requested = cancel_requested AND :status IN ('BOOKED', 'PACKED')""", id, now)
                .param("status", status.name())
                .param("scannedAt", scannedAt == null ? null : utc(scannedAt))
                .query(ShipmentRepository::shipment)
                .single();
    }

    List<Line> lines(UUID shipmentId) {
        return jdbc.sql("SELECT sku, quantity FROM fulfillment.shipment_lines WHERE shipment_id = :id ORDER BY sku")
                .param("id", shipmentId)
                .query((row, rowNumber) -> new Line(row.getString("sku"), row.getInt("quantity")))
                .list();
    }

    /** Adds a step to the tracking history; a carrier event already there is not added again. */
    boolean track(UUID shipmentId, TrackingSource source, String eventId, ShipmentStatus status, String location,
                  Instant occurredAt, boolean applied, Instant now) {
        return jdbc.sql("""
                        INSERT INTO fulfillment.tracking_events (id, shipment_id, source, event_id, status, location,
                                                                 occurred_at, received_at, applied)
                        VALUES (:id, :shipmentId, :source, :eventId, :status, :location, :occurredAt, :now, :applied)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("shipmentId", shipmentId)
                .param("source", source.name())
                .param("eventId", eventId)
                .param("status", status.name())
                .param("location", location)
                .param("occurredAt", utc(occurredAt))
                .param("now", utc(now))
                .param("applied", applied)
                .update() == 1;
    }

    /** The steps that applied, newest first: what the order shows. */
    List<TrackingEntry> appliedTracking(UUID shipmentId) {
        return jdbc.sql("""
                        SELECT status, occurred_at, location FROM fulfillment.tracking_events
                        WHERE shipment_id = :id AND applied
                        ORDER BY occurred_at DESC, received_at DESC
                        """)
                .param("id", shipmentId)
                .query((row, rowNumber) -> new TrackingEntry(ShipmentStatus.valueOf(row.getString("status")),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(), row.getString("location")))
                .list();
    }

    /** The warehouse's work in one status, oldest first, after the shipment {@code after} if there is one. */
    List<Shipment> inStatus(ShipmentStatus status, UUID after, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fulfillment.shipments "
                        + "WHERE status = :status AND (CAST(:after AS uuid) IS NULL OR id > :after) "
                        + "ORDER BY id LIMIT :limit")
                .param("status", status.name())
                .param("after", after)
                .param("limit", limit)
                .query(ShipmentRepository::shipment)
                .list();
    }

    private JdbcClient.StatementSpec update(String assignments, UUID id, Instant now) {
        return jdbc.sql("UPDATE fulfillment.shipments SET " + assignments
                        + ", version = version + 1, updated_at = :now WHERE id = :id RETURNING " + COLUMNS)
                .param("id", id)
                .param("now", utc(now));
    }

    private static Shipment shipment(ResultSet row, int rowNumber) throws SQLException {
        return new Shipment(
                row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("delivery_address_id", UUID.class),
                ShipmentStatus.valueOf(row.getString("status")),
                row.getString("carrier"),
                row.getString("awb"),
                instant(row, "booking_deadline"),
                row.getString("booking_failure"),
                instant(row, "last_scan_at"),
                row.getBoolean("cancel_requested"),
                row.getLong("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Who added a step to the tracking history. */
    enum TrackingSource {
        CARRIER,
        WAREHOUSE
    }
}
