package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.StockReservation.Held;
import com.ecommerce.inventory.StockReservation.Rejected;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Holds past their expiry are swept every minute, or reclaimed by an order that comes up short (LLD §5.6). */
class HoldExpiryTests extends InventoryTest {

    @Test
    void theSweepExpiresOnlyHoldsPastTheirExpiryWholeAndGivesBackEveryLine() {
        receive("INK-1", 5);
        receive("PEN-1", 5);
        UUID due = UUID.randomUUID();
        UUID notDue = UUID.randomUUID();
        UUID committed = UUID.randomUUID();
        reserve(due, line("INK-1", 2), line("PEN-1", 1));
        reserve(notDue, line("INK-1", 1));
        reserve(committed, line("PEN-1", 2));
        reservations.commit(committed);
        expire(due);
        expire(committed);

        sweep();

        assertThat(status(due)).isEqualTo("EXPIRED");
        assertThat(status(notDue)).isEqualTo("HELD");
        assertThat(status(committed)).as("committed reservations never expire").isEqualTo("COMMITTED");
        assertLevels("INK-1", 5, 1);
        assertLevels("PEN-1", 5, 2);
        assertInvariants();
    }

    @Test
    void theSweepWorksThroughABacklogInBatches() {
        receive("BULK-1", 250);
        expiredHolds("BULK-1", 250);
        assertInvariants();

        sweep();

        assertThat(jdbc.sql("SELECT count(*) FROM inventory.reservations WHERE status = 'EXPIRED'")
                .query(Integer.class).single()).isEqualTo(250);
        assertLevels("BULK-1", 250, 0);
        assertInvariants();
    }

    @Test
    void theSweepSkipsAHoldThatIsBeingCommittedOrReleasedAndTakesItNextTime() throws Exception {
        receive("INK-1", 5);
        UUID busy = UUID.randomUUID();
        UUID idle = UUID.randomUUID();
        reserve(busy, line("INK-1", 1));
        reserve(idle, line("INK-1", 1));
        expire(busy);
        expire(idle);

        try (Connection commit = openTransaction()) {
            execute(commit, "SELECT 1 FROM inventory.reservations WHERE order_id = ? FOR UPDATE", busy);
            CompletableFuture.runAsync(this::sweep).get(5, TimeUnit.SECONDS);
            commit.rollback();
        }

        assertThat(List.of(status(busy), status(idle))).containsExactly("HELD", "EXPIRED");
        assertLevels("INK-1", 5, 1);
        sweep();
        assertThat(status(busy)).isEqualTo("EXPIRED");
        assertLevels("INK-1", 5, 0);
    }

    @Test
    void anOrderShortOnlyBecauseOfAnExpiredHoldReclaimsItWithTheSweepStopped() {
        receive("INK-1", 1);
        receive("PEN-1", 3);
        UUID abandoned = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        reserve(abandoned, line("INK-1", 1), line("PEN-1", 2));
        expire(abandoned);

        assertThat(reserve(order, line("INK-1", 1))).isInstanceOf(Held.class);

        assertThat(status(abandoned)).isEqualTo("EXPIRED");
        assertThat(status(order)).isEqualTo("HELD");
        assertLevels("INK-1", 1, 1);
        assertLevels("PEN-1", 3, 0);
        assertInvariants();
    }

    @Test
    void reclaimingExpiresEveryExpiredHoldOnTheSkuInBatches() {
        receive("BULK-1", 150);
        expiredHolds("BULK-1", 150);
        UUID order = UUID.randomUUID();

        assertThat(reserve(order, line("BULK-1", 150))).isInstanceOf(Held.class);

        assertLevels("BULK-1", 150, 150);
        assertInvariants();
    }

    @Test
    void holdsNotYetExpiredAreNeverReclaimed() {
        receive("INK-1", 1);
        UUID first = UUID.randomUUID();
        reserve(first, line("INK-1", 1));

        assertThat(reserve(UUID.randomUUID(), line("INK-1", 1))).isEqualTo(new Rejected("INK-1", 0));

        assertThat(status(first)).isEqualTo("HELD");
        assertLevels("INK-1", 1, 1);
    }

    @Test
    void reclaimingLeavesExpiredHoldsOnOtherSkusToTheSweep() {
        receive("INK-1", 1);
        receive("PEN-1", 1);
        UUID onInk = UUID.randomUUID();
        UUID onPen = UUID.randomUUID();
        reserve(onInk, line("INK-1", 1));
        reserve(onPen, line("PEN-1", 1));
        expire(onInk);
        expire(onPen);

        assertThat(reserve(UUID.randomUUID(), line("INK-1", 1))).isInstanceOf(Held.class);

        assertThat(List.of(status(onInk), status(onPen))).containsExactly("EXPIRED", "HELD");
        sweep();
        assertThat(status(onPen)).isEqualTo("EXPIRED");
        assertLevels("PEN-1", 1, 0);
        assertInvariants();
    }
}
