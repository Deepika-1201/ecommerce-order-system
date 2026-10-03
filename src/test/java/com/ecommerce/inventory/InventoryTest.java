package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.domain.WarehouseStockService;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.support.Eventually;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Starts each test without stock or reservations, and checks the stock invariants of LLD §5.3 and §5.8. */
abstract class InventoryTest extends IntegrationTest {

    /** The payment window, the gateway's grace and the margin (ADR-009). */
    protected static final Duration HOLD = Duration.ofMinutes(50);
    protected static final Caller MEERA = new Caller("meera", Set.of(CallerRole.WAREHOUSE), null, null);

    @Autowired
    protected StockReservations reservations;

    @Autowired
    protected WarehouseStockService warehouse;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected ApplicationContext context;

    @BeforeEach
    void noStock() {
        jdbc.sql("""
                TRUNCATE inventory.reservation_lines, inventory.reservations, inventory.stock_movements,
                         inventory.stock_items
                """).update();
        PlatformTables.clear(jdbc);
    }

    protected void receive(String sku, int quantity) {
        warehouse.receive(MEERA, sku, quantity, null);
    }

    protected static StockReservations.Line line(String sku, int quantity) {
        return new StockReservations.Line(sku, quantity);
    }

    protected StockReservation reserve(UUID order, StockReservations.Line... lines) {
        return reservations.reserve(order, List.of(lines), HOLD);
    }

    /** Moves the order's hold expiry a minute into the past. */
    protected void expire(UUID order) {
        jdbc.sql("UPDATE inventory.reservations SET expires_at = :past WHERE order_id = :order")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .param("order", order)
                .update();
    }

    /** Runs the recurring expiry task once, as the scheduler would every minute. */
    protected void sweep() {
        DueTasks.runRecurring(context, "inventory.expire-holds");
    }

    /**
     * Adds {@code count} held one-unit reservations of the SKU, already past their expiry, straight to the tables;
     * their units are reserved, as if each had been reserved and then left to expire.
     */
    protected void expiredHolds(String sku, int count) {
        jdbc.sql("""
                        WITH held AS (
                            INSERT INTO inventory.reservations (id, order_id, status, expires_at, version, created_at,
                                                                updated_at)
                            SELECT gen_random_uuid(), gen_random_uuid(), 'HELD', now() - interval '1 minute', 1, now(),
                                   now()
                            FROM generate_series(1, :count)
                            RETURNING id)
                        INSERT INTO inventory.reservation_lines (reservation_id, sku, location_code, quantity)
                        SELECT id, :sku, 'BLR1', 1 FROM held
                        """)
                .param("count", count)
                .param("sku", sku)
                .update();
        jdbc.sql("UPDATE inventory.stock_items SET reserved = reserved + :count WHERE sku = :sku")
                .param("count", count)
                .param("sku", sku)
                .update();
    }

    protected void assertLevels(String sku, long onHand, long reserved) {
        assertThat(jdbc.sql("SELECT on_hand, reserved FROM inventory.stock_items WHERE sku = :sku")
                .param("sku", sku)
                .query((row, n) -> List.of(row.getLong("on_hand"), row.getLong("reserved")))
                .single()).as(sku + ": on hand, reserved").containsExactly(onHand, reserved);
    }

    protected String status(UUID order) {
        return jdbc.sql("SELECT status FROM inventory.reservations WHERE order_id = :order")
                .param("order", order)
                .query(String.class)
                .single();
    }

    protected int reservationsOf(UUID order) {
        return jdbc.sql("SELECT count(*) FROM inventory.reservations WHERE order_id = :order")
                .param("order", order)
                .query(Integer.class)
                .single();
    }

    /**
     * For every stock item: {@code 0 ≤ reserved ≤ on_hand}, {@code reserved} equals the units of its {@code HELD} and
     * {@code COMMITTED} reservations, and {@code on_hand} equals the sum of its movements. No such reservation holds a
     * SKU without a stock item.
     */
    protected void assertInvariants() {
        List<String> broken = jdbc.sql("""
                        SELECT s.sku, s.on_hand, s.reserved, coalesce(held.units, 0) AS held,
                               coalesce(moved.units, 0) AS moved
                        FROM inventory.stock_items s
                        LEFT JOIN (SELECT l.sku, l.location_code, sum(l.quantity) AS units
                                   FROM inventory.reservation_lines l
                                   JOIN inventory.reservations r ON r.id = l.reservation_id
                                   WHERE r.status IN ('HELD', 'COMMITTED')
                                   GROUP BY l.sku, l.location_code) held
                            ON held.sku = s.sku AND held.location_code = s.location_code
                        LEFT JOIN (SELECT sku, location_code, sum(quantity) AS units FROM inventory.stock_movements
                                   GROUP BY sku, location_code) moved
                            ON moved.sku = s.sku AND moved.location_code = s.location_code
                        WHERE s.reserved < 0 OR s.reserved > s.on_hand OR s.reserved <> coalesce(held.units, 0)
                           OR s.on_hand <> coalesce(moved.units, 0)
                        """)
                .query((row, n) -> row.getString("sku") + ": on_hand " + row.getLong("on_hand") + ", reserved "
                        + row.getLong("reserved") + ", held by reservations " + row.getLong("held")
                        + ", sum of movements " + row.getLong("moved"))
                .list();
        assertThat(broken).as("stock items breaking an invariant").isEmpty();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.reservation_lines l
                        JOIN inventory.reservations r ON r.id = l.reservation_id
                        WHERE r.status IN ('HELD', 'COMMITTED')
                          AND NOT EXISTS (SELECT 1 FROM inventory.stock_items s
                                          WHERE s.sku = l.sku AND s.location_code = l.location_code)
                        """).query(Integer.class).single()).as("held lines without a stock item").isZero();
    }

    /** Opens a transaction that holds these SKUs' stock rows locked until the connection commits or rolls back. */
    protected Connection lockStockRows(String... skus) throws SQLException {
        Connection gate = openTransaction();
        try (PreparedStatement lock = gate.prepareStatement(
                "SELECT 1 FROM inventory.stock_items WHERE sku = ANY(?) FOR UPDATE")) {
            Array codes = gate.createArrayOf("text", skus);
            lock.setArray(1, codes);
            lock.executeQuery().close();
        }
        return gate;
    }

    /** A connection in a transaction of its own, which stands in for another operation that is midway. */
    protected Connection openTransaction() throws SQLException {
        Connection gate = dataSource.getConnection();
        gate.setAutoCommit(false);
        return gate;
    }

    protected static void execute(Connection gate, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = gate.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    /**
     * Runs {@code count} calls with these SKUs' stock rows locked by another transaction, and releases the lock only
     * when every call is waiting for a lock: they then race exactly where concurrent requests would. The test pool has
     * 10 connections: one for the lock, one for watching, eight for the calls.
     */
    protected <T> List<T> racing(List<String> lockedSkus, int count, IndexedCall<T> call) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        try (Connection gate = lockStockRows(lockedSkus.toArray(String[]::new));
                ExecutorService executor = Executors.newFixedThreadPool(count)) {
            try {
                for (int i = 0; i < count; i++) {
                    int index = i;
                    Callable<T> task = () -> call.call(index);
                    futures.add(executor.submit(task));
                }
                Eventually.await(Duration.ofSeconds(10), count + " calls waiting for a lock",
                        () -> waitingOnLocks() >= count);
            } finally {
                gate.commit();
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    protected int waitingOnLocks() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """)
                .query(Integer.class)
                .single();
    }

    @FunctionalInterface
    protected interface IndexedCall<T> {
        T call(int index) throws Exception;
    }
}
