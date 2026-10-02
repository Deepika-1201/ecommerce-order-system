package com.ecommerce.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.Caller;
import com.ecommerce.platform.CallerRole;
import com.ecommerce.pricing.CouponReservation.Reason;
import com.ecommerce.pricing.domain.CouponCommands.Kind;
import com.ecommerce.pricing.domain.CouponCommands.NewCoupon;
import com.ecommerce.pricing.domain.CouponService;
import com.ecommerce.support.Eventually;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Coupon limits under concurrency, and the redemption state machine (LLD §4.10). */
class CouponRedemptionTests extends IntegrationTest {

    private static final Caller ADMIN = new Caller("pricing-admin", Set.of(CallerRole.ADMIN), null, null);

    @Autowired
    private CouponRedemptions redemptions;

    @Autowired
    private CouponService coupons;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void noCoupons() {
        jdbc.sql("TRUNCATE pricing.coupon_redemptions, pricing.coupons").update();
        PlatformTables.clear(jdbc);
    }

    @Test
    void theLastUseGoesToExactlyOneOfEightRacingOrders() throws Exception {
        UUID coupon = coupon(1, null, true, null);

        List<CouponReservation> outcomes = racing(coupon, 8, i -> redemptions.reserve(UUID.randomUUID(), coupon,
                null));

        assertThat(outcomes).filteredOn(CouponReservation.Held.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(CouponReservation.Unavailable.class::isInstance)
                .containsOnly(new CouponReservation.Unavailable(Reason.EXHAUSTED));
        assertCounters(coupon, 1, 0);
    }

    @Test
    void aCustomerGetsOnlyTheirOwnLimitEvenWhenOrdersRace() throws Exception {
        UUID coupon = coupon(null, 1, true, null);
        UUID customer = UUID.randomUUID();

        List<CouponReservation> outcomes = racing(coupon, 8, i -> redemptions.reserve(UUID.randomUUID(), coupon,
                customer));

        assertThat(outcomes).filteredOn(CouponReservation.Held.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(CouponReservation.Unavailable.class::isInstance)
                .containsOnly(new CouponReservation.Unavailable(Reason.ALREADY_USED));
        assertCounters(coupon, 1, 0);
        assertThat(redemptions.reserve(UUID.randomUUID(), coupon, UUID.randomUUID()))
                .as("another customer").isEqualTo(new CouponReservation.Held());
    }

    @Test
    void reservingAgainForTheSameOrderChangesNothing() {
        UUID coupon = coupon(5, null, true, null);
        UUID order = UUID.randomUUID();

        assertThat(redemptions.reserve(order, coupon, null)).isEqualTo(new CouponReservation.Held());
        assertThat(redemptions.reserve(order, coupon, null)).isEqualTo(new CouponReservation.Held());
        assertCounters(coupon, 1, 0);
    }

    @Test
    void commitAndReleaseMoveTheCountersOnce() {
        UUID coupon = coupon(5, null, true, null);
        UUID paid = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        UUID refunded = UUID.randomUUID();
        redemptions.reserve(paid, coupon, null);
        redemptions.reserve(rejected, coupon, null);
        redemptions.reserve(refunded, coupon, null);

        redemptions.commit(paid);
        redemptions.commit(paid);
        redemptions.release(rejected);
        redemptions.release(rejected);
        redemptions.commit(rejected);
        redemptions.commit(refunded);
        redemptions.release(refunded);

        assertCounters(coupon, 0, 1);
        assertThat(status(paid)).isEqualTo("COMMITTED");
        assertThat(status(rejected)).isEqualTo("RELEASED");
        assertThat(status(refunded)).isEqualTo("RELEASED");
        assertThat(redemptions.reserve(rejected, coupon, null))
                .isEqualTo(new CouponReservation.Unavailable(Reason.RELEASED));
        redemptions.commit(UUID.randomUUID());
        redemptions.release(UUID.randomUUID());
        assertCounters(coupon, 0, 1);
    }

    @Test
    void aReleasedUseCanBeTakenAgain() {
        UUID coupon = coupon(1, null, true, null);
        UUID first = UUID.randomUUID();
        redemptions.reserve(first, coupon, null);

        assertThat(redemptions.reserve(UUID.randomUUID(), coupon, null))
                .isEqualTo(new CouponReservation.Unavailable(Reason.EXHAUSTED));
        redemptions.release(first);
        assertThat(redemptions.reserve(UUID.randomUUID(), coupon, null)).isEqualTo(new CouponReservation.Held());
    }

    @Test
    void anInactiveOrOutOfWindowCouponIsUnavailable() {
        Instant now = Instant.now();
        UUID inactive = coupon(null, null, false, null);
        UUID ended = coupon(null, null, true, new Instant[] {now.minus(2, ChronoUnit.DAYS), now.minusSeconds(1)});
        UUID future = coupon(null, null, true, new Instant[] {now.plus(1, ChronoUnit.DAYS), null});

        assertThat(redemptions.reserve(UUID.randomUUID(), inactive, null))
                .isEqualTo(new CouponReservation.Unavailable(Reason.INACTIVE));
        assertThat(redemptions.reserve(UUID.randomUUID(), ended, null))
                .isEqualTo(new CouponReservation.Unavailable(Reason.OUTSIDE_WINDOW));
        assertThat(redemptions.reserve(UUID.randomUUID(), future, null))
                .isEqualTo(new CouponReservation.Unavailable(Reason.OUTSIDE_WINDOW));
        assertCounters(inactive, 0, 0);
    }

    @Test
    void theCountersAlwaysEqualTheRedemptions() {
        UUID coupon = coupon(8, null, true, null);
        Random random = new Random(42);
        List<UUID> orders = new ArrayList<>();
        for (int step = 0; step < 200; step++) {
            int action = random.nextInt(3);
            if (action == 0 || orders.isEmpty()) {
                UUID order = UUID.randomUUID();
                orders.add(order);
                redemptions.reserve(order, coupon, null);
            } else if (action == 1) {
                redemptions.commit(orders.get(random.nextInt(orders.size())));
            } else {
                redemptions.release(orders.get(random.nextInt(orders.size())));
            }
            int held = count(coupon, "HELD");
            int committed = count(coupon, "COMMITTED");
            assertCounters(coupon, held, committed);
            assertThat(held + committed).isLessThanOrEqualTo(8);
        }
    }

    /** A coupon with the given limits, and {@code window} = {from, until}, or {@code null} for the defaults. */
    private UUID coupon(Integer totalLimit, Integer perCustomerLimit, boolean active, Instant[] window) {
        return coupons.create(ADMIN, new NewCoupon("C-" + UUID.randomUUID().toString().substring(0, 8), Kind.FLAT,
                null, null, 5_000L, null, window == null ? null : window[0], window == null ? null : window[1],
                totalLimit, perCustomerLimit, active)).id();
    }

    /**
     * Runs {@code count} calls with the coupon's row locked by another transaction, and releases the lock only when
     * every call is waiting on it: they then race exactly where concurrent requests would. The test pool has 10
     * connections: one for the lock, one for watching, eight for the calls.
     */
    private <T> List<T> racing(UUID coupon, int count, IndexedCall<T> call) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        try (Connection gate = dataSource.getConnection();
                ExecutorService executor = Executors.newFixedThreadPool(count)) {
            gate.setAutoCommit(false);
            try (PreparedStatement lock = gate.prepareStatement(
                    "SELECT 1 FROM pricing.coupons WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, coupon);
                lock.executeQuery().close();
            }
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> task = () -> call.call(index);
                futures.add(executor.submit(task));
            }
            Eventually.await(Duration.ofSeconds(10), count + " reservations waiting on the coupon's lock",
                    () -> waitingOnLocks() >= count);
            gate.commit();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private int waitingOnLocks() {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """)
                .query(Integer.class)
                .single();
    }

    private void assertCounters(UUID coupon, int reserved, int redeemed) {
        assertThat(jdbc.sql("SELECT reserved, redeemed FROM pricing.coupons WHERE id = ?")
                .param(coupon)
                .query((row, n) -> List.of(row.getInt("reserved"), row.getInt("redeemed")))
                .single()).containsExactly(reserved, redeemed);
    }

    private int count(UUID coupon, String status) {
        return jdbc.sql("SELECT count(*) FROM pricing.coupon_redemptions WHERE coupon_id = ? AND status = ?")
                .params(coupon, status)
                .query(Integer.class)
                .single();
    }

    private String status(UUID order) {
        return jdbc.sql("SELECT status FROM pricing.coupon_redemptions WHERE order_id = ?")
                .param(order)
                .query(String.class)
                .single();
    }

    @FunctionalInterface
    private interface IndexedCall<T> {
        T call(int index) throws Exception;
    }
}
