package com.ecommerce.inventory.domain;

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
 * SQL for stock items and their movements. The counters change only by conditional updates, never read-modify-write;
 * each update locks the row until the transaction ends (LLD §5.3, §5.7).
 */
@Repository
class StockRepository {

    private static final String LEVEL_COLUMNS = "sku, location_code, on_hand, reserved, version, updated_at";
    private static final String MOVEMENT_COLUMNS = """
            id, sku, location_code, kind, quantity, reason, note, reference, order_id, on_hand_after, actor_id, created_at
            """;

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the units into {@code reserved} if that many are available; false if not, or without a stock item. */
    boolean take(ReservedLine line, Instant now) {
        return jdbc.sql("""
                        UPDATE inventory.stock_items
                        SET reserved = reserved + :quantity, version = version + 1, updated_at = :now
                        WHERE sku = :sku AND location_code = :location AND on_hand - reserved >= :quantity
                        """)
                .param("quantity", line.quantity())
                .param("now", utc(now))
                .param("sku", line.sku())
                .param("location", line.locationCode())
                .update() == 1;
    }

    /** Units available now, without waiting for a lock; 0 without a stock item. */
    long available(ReservedLine line) {
        return find(line.sku(), line.locationCode()).map(StockLevel::available).orElse(0L);
    }

    /** Gives held or committed units back to {@code available}. */
    void giveBack(ReservedLine line, Instant now) {
        changeExactlyOne("reserved = reserved - :quantity", line, now);
    }

    /** Committed units leave the warehouse; returns {@code on_hand} after. */
    long handOver(ReservedLine line, Instant now) {
        return changeExactlyOne("on_hand = on_hand - :quantity, reserved = reserved - :quantity", line, now);
    }

    /** Returned units are back on hand; returns {@code on_hand} after. */
    long restock(ReservedLine line, Instant now) {
        return changeExactlyOne("on_hand = on_hand + :quantity", line, now);
    }

    /** Adds received units, creating the stock item on the first receipt. */
    StockLevel receive(String sku, String location, int quantity, Instant now) {
        return jdbc.sql("""
                        INSERT INTO inventory.stock_items AS s
                            (sku, location_code, on_hand, reserved, version, created_at, updated_at)
                        VALUES (:sku, :location, :quantity, 0, 1, :now, :now)
                        ON CONFLICT (sku, location_code) DO UPDATE
                        SET on_hand = s.on_hand + EXCLUDED.on_hand, version = s.version + 1,
                            updated_at = EXCLUDED.updated_at
                        RETURNING s.sku, s.location_code, s.on_hand, s.reserved, s.version, s.updated_at
                        """)
                .param("sku", sku)
                .param("location", location)
                .param("quantity", quantity)
                .param("now", utc(now))
                .query(StockRepository::level)
                .single();
    }

    /** Changes {@code on_hand} unless it would drop below {@code reserved}; empty if it would, or without an item. */
    Optional<StockLevel> adjust(String sku, String location, int quantityChange, Instant now) {
        return jdbc.sql("""
                        UPDATE inventory.stock_items
                        SET on_hand = on_hand + :change, version = version + 1, updated_at = :now
                        WHERE sku = :sku AND location_code = :location AND on_hand + :change >= reserved
                        RETURNING sku, location_code, on_hand, reserved, version, updated_at
                        """)
                .param("change", quantityChange)
                .param("now", utc(now))
                .param("sku", sku)
                .param("location", location)
                .query(StockRepository::level)
                .optional();
    }

    Optional<StockLevel> find(String sku, String location) {
        return jdbc.sql("SELECT " + LEVEL_COLUMNS
                        + " FROM inventory.stock_items WHERE sku = :sku AND location_code = :location")
                .param("sku", sku)
                .param("location", location)
                .query(StockRepository::level)
                .optional();
    }

    /** In SKU order, after the given SKU; one more than {@code limit}, to know whether a page follows. */
    List<StockLevel> list(String location, String afterSku, int limit) {
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + LEVEL_COLUMNS
                        + " FROM inventory.stock_items WHERE location_code = :location"
                        + (afterSku != null ? " AND sku > :after" : "") + " ORDER BY sku LIMIT :limit")
                .param("location", location)
                .param("limit", limit + 1);
        if (afterSku != null) {
            statement = statement.param("after", afterSku);
        }
        return statement.query(StockRepository::level).list();
    }

    void insertMovement(String sku, String location, MovementKind kind, long quantity, AdjustmentReason reason,
            String note, String reference, UUID orderId, long onHandAfter, String actorId, Instant now) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_movements (sku, location_code, kind, quantity, reason, note, reference,
                                                               order_id, on_hand_after, actor_id, created_at)
                        VALUES (:sku, :location, :kind, :quantity, :reason, :note, :reference, :orderId, :onHandAfter,
                                :actorId, :now)
                        """)
                .param("sku", sku)
                .param("location", location)
                .param("kind", kind.name())
                .param("quantity", quantity)
                .param("reason", reason == null ? null : reason.name())
                .param("note", note)
                .param("reference", reference)
                .param("orderId", orderId)
                .param("onHandAfter", onHandAfter)
                .param("actorId", actorId)
                .param("now", utc(now))
                .update();
    }

    /** Newest first, before the given movement id; one more than {@code limit}, to know whether a page follows. */
    List<StockMovement> movements(String sku, String location, Long beforeId, int limit) {
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT " + MOVEMENT_COLUMNS
                        + " FROM inventory.stock_movements WHERE sku = :sku AND location_code = :location"
                        + (beforeId != null ? " AND id < :before" : "") + " ORDER BY id DESC LIMIT :limit")
                .param("sku", sku)
                .param("location", location)
                .param("limit", limit + 1);
        if (beforeId != null) {
            statement = statement.param("before", beforeId);
        }
        return statement.query(StockRepository::movement).list();
    }

    private long changeExactlyOne(String change, ReservedLine line, Instant now) {
        return jdbc.sql("UPDATE inventory.stock_items SET " + change + ", version = version + 1, updated_at = :now"
                        + " WHERE sku = :sku AND location_code = :location RETURNING on_hand")
                .param("quantity", line.quantity())
                .param("now", utc(now))
                .param("sku", line.sku())
                .param("location", line.locationCode())
                .query(Long.class)
                .single();
    }

    private static StockLevel level(ResultSet row, int rowNumber) throws SQLException {
        return new StockLevel(row.getString("sku"), row.getString("location_code"), row.getLong("on_hand"),
                row.getLong("reserved"), row.getLong("version"), instant(row, "updated_at"));
    }

    private static StockMovement movement(ResultSet row, int rowNumber) throws SQLException {
        String reason = row.getString("reason");
        return new StockMovement(
                row.getLong("id"),
                row.getString("sku"),
                row.getString("location_code"),
                MovementKind.valueOf(row.getString("kind")),
                row.getInt("quantity"),
                reason == null ? null : AdjustmentReason.valueOf(reason),
                row.getString("note"),
                row.getString("reference"),
                row.getObject("order_id", UUID.class),
                row.getLong("on_hand_after"),
                row.getString("actor_id"),
                instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
