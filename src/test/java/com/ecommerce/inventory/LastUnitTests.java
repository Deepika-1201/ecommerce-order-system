package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.StockReservation.Held;
import com.ecommerce.inventory.StockReservation.Rejected;
import com.ecommerce.support.Eventually;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Orders racing for the same stock or reservation (LLD §5.4–§5.7, exit criterion of phase 5). Another transaction
 * holds the contended row until the callers queue on it, so the lock timeout is long enough for the slowest of them.
 */
@TestPropertySource(properties = "ecom.inventory.lock-timeout=10s")
class LastUnitTests extends InventoryTest {

    @Test
    void theRacesWaitLongerThanTheDefaultLockTimeout() {
        assertThat(context.getEnvironment().getProperty("ecom.inventory.lock-timeout")).isEqualTo("10s");
    }

    @Test
    void exactlyOneOfEightOrdersGetsTheLastUnit() throws Exception {
        receive("LAST-1", 1);

        List<StockReservation> outcomes = racing(List.of("LAST-1"), 8,
                i -> reserve(UUID.randomUUID(), line("LAST-1", 1)));

        assertThat(outcomes).filteredOn(Held.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(Rejected.class::isInstance).hasSize(7)
                .containsOnly(new Rejected("LAST-1", 0));
        assertLevels("LAST-1", 1, 1);
        assertInvariants();
    }

    @Test
    void ordersListingTwoSkusInEitherOrderNeverDeadlockAndHoldBothLinesOrNeither() throws Exception {
        receive("PAIR-A", 4);
        receive("PAIR-B", 3);

        List<StockReservation> outcomes = racing(List.of("PAIR-A"), 8, i -> i % 2 == 0
                ? reserve(UUID.randomUUID(), line("PAIR-A", 1), line("PAIR-B", 1))
                : reserve(UUID.randomUUID(), line("PAIR-B", 1), line("PAIR-A", 1)));

        assertThat(outcomes).filteredOn(Held.class::isInstance).hasSize(3);
        assertThat(outcomes).filteredOn(Rejected.class::isInstance).hasSize(5)
                .containsOnly(new Rejected("PAIR-B", 0));
        assertLevels("PAIR-A", 4, 3);
        assertLevels("PAIR-B", 3, 3);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.reservation_lines l
                        JOIN inventory.reservations r ON r.id = l.reservation_id
                        WHERE r.status = 'HELD'
                        """).query(Integer.class).single()).as("both lines of each held order").isEqualTo(6);
        assertInvariants();
    }

    @Test
    void aRepeatRacingTheFirstAttemptGetsTheSameOutcome() throws Exception {
        receive("LAST-1", 5);
        UUID order = UUID.randomUUID();

        List<StockReservation> outcomes = racing(List.of("LAST-1"), 2, i -> reserve(order, line("LAST-1", 2)));

        assertThat(outcomes.get(0)).isInstanceOf(Held.class).isEqualTo(outcomes.get(1));
        assertThat(reservationsOf(order)).isEqualTo(1);
        assertLevels("LAST-1", 5, 2);
    }

    @Test
    void aCommitWaitingForAConcurrentReleaseSeesIt() throws Exception {
        receive("LAST-1", 2);
        UUID order = UUID.randomUUID();
        reserve(order, line("LAST-1", 2));

        try (Connection release = openTransaction()) {
            execute(release, "UPDATE inventory.reservations SET status = 'RELEASED' WHERE order_id = ?", order);
            execute(release, "UPDATE inventory.stock_items SET reserved = reserved - 2 WHERE sku = 'LAST-1'");
            CompletableFuture<CommitResult> commit = CompletableFuture.supplyAsync(() -> reservations.commit(order));
            Eventually.await(Duration.ofSeconds(10), "the commit waiting", () -> waitingOnLocks() >= 1
                    || commit.isDone());
            release.commit();

            assertThat(commit.get(10, TimeUnit.SECONDS)).isEqualTo(CommitResult.LOST);
        }
        assertThat(status(order)).isEqualTo("RELEASED");
        assertLevels("LAST-1", 2, 0);
        assertInvariants();
    }

    @Test
    void anOrderShortOfUnitsThatAnotherTransactionIsReclaimingWaitsForThem() throws Exception {
        receive("LAST-1", 1);
        UUID abandoned = UUID.randomUUID();
        reserve(abandoned, line("LAST-1", 1));
        expire(abandoned);

        try (Connection sweep = openTransaction()) {
            execute(sweep, "UPDATE inventory.reservations SET status = 'EXPIRED' WHERE order_id = ?", abandoned);
            CompletableFuture<StockReservation> order = CompletableFuture.supplyAsync(() ->
                    reserve(UUID.randomUUID(), line("LAST-1", 1)));
            Eventually.await(Duration.ofSeconds(10), "the order waiting", () -> waitingOnLocks() >= 1
                    || order.isDone());
            execute(sweep, "UPDATE inventory.stock_items SET reserved = reserved - 1 WHERE sku = 'LAST-1'");
            sweep.commit();

            assertThat(order.get(10, TimeUnit.SECONDS)).isInstanceOf(Held.class);
        }
        assertLevels("LAST-1", 1, 1);
        assertInvariants();
    }
}
