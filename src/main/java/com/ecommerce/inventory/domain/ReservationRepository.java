package com.ecommerce.inventory.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for reservations and their lines (LLD §5.4–§5.6). */
@Repository
class ReservationRepository {

    private static final String COLUMNS = "id, order_id, status, expires_at, short_sku, short_available";

    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Reservation> find(UUID orderId) {
        return header(orderId, "").map(this::withLines);
    }

    /** Locks the order's reservation until the transaction ends. */
    Optional<Reservation> lock(UUID orderId) {
        return header(orderId, " FOR UPDATE").map(this::withLines);
    }

    /**
     * Inserts the reservation with its lines. {@code order_id} is unique: while another transaction has inserted the
     * same order, this waits for it to end, then fails with {@code DuplicateKeyException} if it committed.
     */
    void insert(UUID id, UUID orderId, ReservationStatus status, Instant expiresAt, String shortSku,
            Long shortAvailable, List<ReservedLine> lines, Instant now) {
        jdbc.sql("""
                        INSERT INTO inventory.reservations (id, order_id, status, expires_at, short_sku, short_available,
                                                            version, created_at, updated_at)
                        VALUES (:id, :orderId, :status, :expiresAt, :shortSku, :shortAvailable, 1, :now, :now)
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("status", status.name())
                .param("expiresAt", utc(expiresAt))
                .param("shortSku", shortSku)
                .param("shortAvailable", shortAvailable)
                .param("now", utc(now))
                .update();
        jdbc.sql("""
                        INSERT INTO inventory.reservation_lines (reservation_id, sku, location_code, quantity)
                        SELECT :id, line.sku, line.location_code, line.quantity
                        FROM unnest(CAST(:skus AS text[]), CAST(:locations AS text[]), CAST(:quantities AS integer[]))
                            AS line (sku, location_code, quantity)
                        """)
                .param("id", id)
                .param("skus", lines.stream().map(ReservedLine::sku).toArray(String[]::new))
                .param("locations", lines.stream().map(ReservedLine::locationCode).toArray(String[]::new))
                .param("quantities", lines.stream().map(line -> Math.toIntExact(line.quantity()))
                        .toArray(Integer[]::new))
                .update();
    }

    void setStatus(UUID id, ReservationStatus status, Instant now) {
        jdbc.sql("""
                        UPDATE inventory.reservations SET status = :status, version = version + 1, updated_at = :now
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("now", utc(now))
                .param("id", id)
                .update();
    }

    /**
     * Marks up to {@code limit} held reservations past their expiry as {@code EXPIRED}, oldest first, skipping any that
     * another transaction has locked; returns their ids.
     */
    List<UUID> expireDue(Instant now, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM inventory.reservations
                            WHERE status = 'HELD' AND expires_at <= :now
                            ORDER BY expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        UPDATE inventory.reservations r
                        SET status = 'EXPIRED', version = r.version + 1, updated_at = :now
                        FROM due WHERE r.id = due.id
                        RETURNING r.id
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * As {@link #expireDue}, for the held reservations with a line of one of these SKUs at the location. It waits for
     * any that another transaction has locked, so a hold that is being expired elsewhere has its units back before
     * this returns; it locks in id order, so two such calls never wait for each other in a cycle.
     */
    List<UUID> expireDueHolding(Collection<String> skus, String location, Instant now, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT r.id FROM inventory.reservations r
                            WHERE r.status = 'HELD' AND r.expires_at <= :now
                              AND EXISTS (SELECT 1 FROM inventory.reservation_lines l
                                          WHERE l.reservation_id = r.id AND l.sku = ANY(:skus)
                                            AND l.location_code = :location)
                            ORDER BY r.id
                            LIMIT :limit
                            FOR UPDATE OF r)
                        UPDATE inventory.reservations r
                        SET status = 'EXPIRED', version = r.version + 1, updated_at = :now
                        FROM due WHERE r.id = due.id
                        RETURNING r.id
                        """)
                .param("now", utc(now))
                .param("skus", skus.toArray(String[]::new))
                .param("location", location)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** The units these reservations hold, per SKU and location. */
    List<ReservedLine> unitsHeldBy(List<UUID> reservationIds) {
        return jdbc.sql("""
                        SELECT sku, location_code, sum(quantity) AS quantity FROM inventory.reservation_lines
                        WHERE reservation_id = ANY(CAST(:ids AS uuid[]))
                        GROUP BY sku, location_code
                        """)
                .param("ids", reservationIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((row, rowNumber) -> new ReservedLine(row.getString("sku"), row.getString("location_code"),
                        row.getLong("quantity")))
                .list();
    }

    private Optional<Reservation> header(UUID orderId, String lock) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM inventory.reservations WHERE order_id = :orderId" + lock)
                .param("orderId", orderId)
                .query(ReservationRepository::reservation)
                .optional();
    }

    private Reservation withLines(Reservation header) {
        List<ReservedLine> lines = jdbc.sql("""
                        SELECT sku, location_code, quantity FROM inventory.reservation_lines
                        WHERE reservation_id = :id
                        """)
                .param("id", header.id())
                .query((row, rowNumber) -> new ReservedLine(row.getString("sku"), row.getString("location_code"),
                        row.getLong("quantity")))
                .list()
                .stream()
                .sorted(ReservedLine.LOCK_ORDER)
                .toList();
        return new Reservation(header.id(), header.orderId(), header.status(), header.expiresAt(), header.shortSku(),
                header.shortAvailable(), lines);
    }

    private static Reservation reservation(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
        return new Reservation(
                row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class),
                ReservationStatus.valueOf(row.getString("status")),
                expiresAt == null ? null : expiresAt.toInstant(),
                row.getString("short_sku"),
                row.getObject("short_available", Long.class),
                List.of());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
