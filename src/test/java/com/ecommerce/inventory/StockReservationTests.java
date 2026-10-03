package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.StockReservation.Held;
import com.ecommerce.inventory.StockReservation.Rejected;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserving all lines or none, and every transition of LLD §5.5 with its repeats and errors. */
class StockReservationTests extends InventoryTest {

    private final UUID order = UUID.randomUUID();

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void everyLineIsHeldUntilTheHoldEnds() {
        receive("INK-1", 5);
        receive("PEN-1", 3);
        Instant before = Instant.now();

        StockReservation outcome = reservations.reserve(order, List.of(line("PEN-1", 3), line("INK-1", 2)), HOLD);

        assertThat(outcome).isInstanceOf(Held.class);
        assertThat(((Held) outcome).expiresAt()).isBetween(before.plus(HOLD), Instant.now().plus(HOLD));
        assertThat(status(order)).isEqualTo("HELD");
        assertLevels("INK-1", 5, 2);
        assertLevels("PEN-1", 3, 3);
        assertInvariants();
    }

    @Test
    void aShortLineRollsBackTheLinesTakenBeforeIt() {
        receive("INK-1", 5);
        receive("PEN-1", 1);

        StockReservation outcome = reserve(order, line("PEN-1", 2), line("INK-1", 2));

        assertThat(outcome).isEqualTo(new Rejected("PEN-1", 1));
        assertThat(status(order)).isEqualTo("REJECTED");
        assertLevels("INK-1", 5, 0);
        assertLevels("PEN-1", 1, 0);
        assertInvariants();
    }

    @Test
    void theRejectionNamesTheFirstShortLineInSkuOrder() {
        receive("INK-1", 1);
        receive("PEN-1", 1);

        assertThat(reserve(order, line("PEN-1", 2), line("INK-1", 3))).isEqualTo(new Rejected("INK-1", 1));
        assertThat(reserve(UUID.randomUUID(), line("ZINC-1", 1), line("GHOST-1", 1)))
                .as("a SKU without a stock item has nothing available").isEqualTo(new Rejected("GHOST-1", 0));
    }

    @Test
    void aRepeatReturnsTheOriginalOutcomeWhateverHappenedSince() {
        receive("INK-1", 1);
        UUID rejected = UUID.randomUUID();
        StockReservation held = reserve(order, line("INK-1", 1));
        StockReservation rejection = reserve(rejected, line("INK-1", 1));
        reservations.release(order);
        receive("INK-1", 5);

        assertThat(reserve(order, line("INK-1", 1))).isEqualTo(held);
        assertThat(reserve(rejected, line("INK-1", 1))).isEqualTo(rejection).isEqualTo(new Rejected("INK-1", 0));
        assertLevels("INK-1", 6, 0);
        assertThat(reservationsOf(order)).isEqualTo(1);
    }

    @Test
    void aRepeatWithOtherLinesIsAProgrammingError() {
        receive("INK-1", 5);
        receive("PEN-1", 5);
        reserve(order, line("INK-1", 1));

        assertThatThrownBy(() -> reserve(order, line("INK-1", 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reserve(order, line("INK-1", 1), line("PEN-1", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reserve(order, line("INK-1", 1))).isInstanceOf(Held.class);
        assertLevels("INK-1", 5, 1);
        assertLevels("PEN-1", 5, 0);
    }

    @Test
    void linesAndHoldsAreChecked() {
        receive("INK-1", 5);

        assertThatThrownBy(() -> reservations.reserve(order, List.of(), HOLD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reserve(order, line("INK-1", 1), line("INK-1", 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line("INK-1", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        for (Duration hold : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(1).plusNanos(1000))) {
            assertThatThrownBy(() -> reservations.reserve(order, List.of(line("INK-1", 1)), hold))
                    .as("hold %s", hold).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(reservationsOf(order)).isZero();
        assertThat(reservations.reserve(order, List.of(line("INK-1", 1)), Duration.ofDays(1)))
                .isInstanceOf(Held.class);
    }

    @Test
    void commitKeepsTheUnitsReservedAndRepeatsChangeNothing() {
        receive("INK-1", 5);
        reserve(order, line("INK-1", 2));

        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);
        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);

        assertThat(status(order)).isEqualTo("COMMITTED");
        assertLevels("INK-1", 5, 2);
        assertInvariants();
    }

    @Test
    void aHoldPastItsExpiryStillCommitsUntilItIsReclaimed() {
        receive("INK-1", 2);
        reserve(order, line("INK-1", 2));
        expire(order);

        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);
        sweep();

        assertThat(status(order)).isEqualTo("COMMITTED");
        assertLevels("INK-1", 2, 2);
    }

    @Test
    void commitTakesAnExpiredHoldsStockAgain() {
        receive("INK-1", 3);
        receive("PEN-1", 1);
        reserve(order, line("INK-1", 2), line("PEN-1", 1));
        expire(order);
        sweep();
        assertThat(status(order)).isEqualTo("EXPIRED");
        assertLevels("INK-1", 3, 0);

        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);

        assertThat(status(order)).isEqualTo("COMMITTED");
        assertLevels("INK-1", 3, 2);
        assertLevels("PEN-1", 1, 1);
        assertInvariants();
    }

    @Test
    void commitReclaimsOtherExpiredHoldsBeforeGivingUp() {
        receive("INK-1", 2);
        UUID abandoned = UUID.randomUUID();
        reserve(order, line("INK-1", 2));
        expire(order);
        sweep();
        reserve(abandoned, line("INK-1", 2));
        expire(abandoned);

        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);

        assertThat(status(abandoned)).isEqualTo("EXPIRED");
        assertLevels("INK-1", 2, 2);
        assertInvariants();
    }

    @Test
    void commitAnswersLostWhenTheStockWentToAnotherOrderOrWasReleased() {
        receive("INK-1", 2);
        receive("PEN-1", 5);
        reserve(order, line("PEN-1", 1), line("INK-1", 2));
        expire(order);
        assertThat(reserve(UUID.randomUUID(), line("INK-1", 1))).as("reclaims the expired hold")
                .isInstanceOf(Held.class);
        UUID released = UUID.randomUUID();
        reserve(released, line("PEN-1", 1));
        reservations.release(released);

        assertThat(reservations.commit(order)).isEqualTo(CommitResult.LOST);
        assertThat(reservations.commit(order)).isEqualTo(CommitResult.LOST);
        assertThat(reservations.commit(released)).isEqualTo(CommitResult.LOST);

        assertThat(status(order)).isEqualTo("EXPIRED");
        assertThat(status(released)).isEqualTo("RELEASED");
        assertLevels("INK-1", 2, 1);
        assertLevels("PEN-1", 5, 0);
        assertInvariants();
    }

    @Test
    void releaseGivesTheUnitsBackOnce() {
        receive("INK-1", 5);
        UUID committed = UUID.randomUUID();
        reserve(order, line("INK-1", 2));
        reserve(committed, line("INK-1", 1));
        reservations.commit(committed);

        reservations.release(order);
        reservations.release(order);
        reservations.release(committed);
        reservations.release(committed);

        assertThat(status(order)).isEqualTo("RELEASED");
        assertThat(status(committed)).isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT version FROM inventory.reservations WHERE order_id = :order")
                .param("order", committed).query(Long.class).single()).as("reserved, committed, released").isEqualTo(3);
        assertLevels("INK-1", 5, 0);
        assertInvariants();
    }

    @Test
    void operationsCommitApartFromTheCallersTransaction() {
        receive("INK-1", 5);

        transactions.executeWithoutResult(callers -> {
            reserve(order, line("INK-1", 2));
            callers.setRollbackOnly();
        });

        assertThat(status(order)).isEqualTo("HELD");
        assertLevels("INK-1", 5, 2);
    }

    @Test
    void releasingAReservationThatHoldsNothingChangesNothing() {
        receive("INK-1", 1);
        UUID expired = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        UUID holding = UUID.randomUUID();
        reserve(expired, line("INK-1", 1));
        expire(expired);
        sweep();
        reserve(holding, line("INK-1", 1));
        reserve(rejected, line("INK-1", 1));

        reservations.release(expired);
        reservations.release(rejected);
        reservations.release(UUID.randomUUID());

        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(status(rejected)).isEqualTo("REJECTED");
        assertLevels("INK-1", 1, 1);
        assertInvariants();
    }

    @Test
    void aHandoverTakesTheUnitsOutOfStockWithAMovementPerLine() {
        receive("INK-1", 5);
        receive("PEN-1", 2);
        reserve(order, line("INK-1", 2), line("PEN-1", 1));
        reservations.commit(order);

        reservations.fulfill(order);
        reservations.fulfill(order);

        assertThat(status(order)).isEqualTo("FULFILLED");
        assertLevels("INK-1", 3, 0);
        assertLevels("PEN-1", 1, 0);
        assertThat(orderMovements()).containsExactlyInAnyOrder(
                "INK-1 HANDOVER -2 on hand 3", "PEN-1 HANDOVER -1 on hand 1");
        assertThat(reservations.commit(order)).as("a late repeat").isEqualTo(CommitResult.COMMITTED);
        assertInvariants();
    }

    @Test
    void aReturnPutsTheUnitsBackOnHandWithAMovementPerLine() {
        receive("INK-1", 5);
        reserve(order, line("INK-1", 2));
        reservations.commit(order);
        reservations.fulfill(order);

        reservations.restockReturn(order);
        reservations.restockReturn(order);
        reservations.fulfill(order);

        assertThat(status(order)).isEqualTo("RETURNED");
        assertLevels("INK-1", 5, 0);
        assertThat(orderMovements()).containsExactlyInAnyOrder("INK-1 HANDOVER -2 on hand 3",
                "INK-1 RETURN 2 on hand 5");
        assertThat(reservations.commit(order)).isEqualTo(CommitResult.COMMITTED);
        assertInvariants();
    }

    @Test
    void transitionsThatCannotHappenAreErrorsAndChangeNothing() {
        receive("INK-1", 10);
        UUID held = held();
        UUID committed = held();
        reservations.commit(committed);
        UUID fulfilled = held();
        reservations.commit(fulfilled);
        reservations.fulfill(fulfilled);
        UUID returned = held();
        reservations.commit(returned);
        reservations.fulfill(returned);
        reservations.restockReturn(returned);
        UUID released = held();
        reservations.release(released);
        UUID expired = held();
        expire(expired);
        sweep();
        UUID rejected = UUID.randomUUID();
        reserve(rejected, line("GHOST-1", 1));
        UUID none = UUID.randomUUID();

        assertError(() -> reservations.commit(rejected));
        assertError(() -> reservations.commit(none));
        assertError(() -> reservations.release(fulfilled));
        assertError(() -> reservations.release(returned));
        for (UUID notCommitted : List.of(held, expired, released, rejected, none)) {
            assertError(() -> reservations.fulfill(notCommitted));
        }
        for (UUID notFulfilled : List.of(held, committed, expired, released, rejected, none)) {
            assertError(() -> reservations.restockReturn(notFulfilled));
        }

        assertThat(List.of(status(held), status(committed), status(fulfilled), status(returned), status(released),
                status(expired), status(rejected)))
                .containsExactly("HELD", "COMMITTED", "FULFILLED", "RETURNED", "RELEASED", "EXPIRED", "REJECTED");
        assertThat(reservationsOf(none)).isZero();
        assertLevels("INK-1", 9, 2);
        assertInvariants();
    }

    /** A new order holding one unit of INK-1. */
    private UUID held() {
        UUID id = UUID.randomUUID();
        assertThat(reserve(id, line("INK-1", 1))).isInstanceOf(Held.class);
        return id;
    }

    private List<String> orderMovements() {
        return jdbc.sql("""
                        SELECT sku, kind, quantity, on_hand_after FROM inventory.stock_movements
                        WHERE order_id = :order AND actor_id IS NULL
                        """)
                .param("order", order)
                .query((row, n) -> row.getString("sku") + " " + row.getString("kind") + " " + row.getInt("quantity")
                        + " on hand " + row.getLong("on_hand_after"))
                .list();
    }

    private void assertError(Runnable transition) {
        assertThatThrownBy(transition::run).isInstanceOf(IllegalStateException.class);
    }
}
