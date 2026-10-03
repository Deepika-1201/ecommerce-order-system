package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.StockReservation.Held;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

/** A stock row locked longer than the lock timeout (500 ms) fails the operation, which changes nothing (LLD §5.7). */
class StockLockTimeoutTests extends InventoryTest {

    private final UUID order = UUID.randomUUID();

    @Test
    void aReservationThatWaitsTooLongFailsAndRecordsNothing() throws Exception {
        receive("BUSY-1", 5);

        try (Connection gate = lockStockRows("BUSY-1")) {
            try {
                long started = System.nanoTime();
                CompletableFuture<StockReservation> waiting = CompletableFuture.supplyAsync(() ->
                        reserve(order, line("BUSY-1", 1)));
                assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(CannotAcquireLockException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(500));
            } finally {
                gate.rollback();
            }
        }

        assertThat(reservationsOf(order)).isZero();
        assertLevels("BUSY-1", 5, 0);
        assertThat(reserve(order, line("BUSY-1", 1))).as("run again").isInstanceOf(Held.class);
        assertInvariants();
    }

    @Test
    void aReleaseThatWaitsTooLongFailsAndChangesNothing() throws Exception {
        receive("BUSY-1", 5);
        reserve(order, line("BUSY-1", 2));

        try (Connection gate = lockStockRows("BUSY-1")) {
            try {
                CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> reservations.release(order));
                assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(CannotAcquireLockException.class);
            } finally {
                gate.rollback();
            }
        }

        assertThat(status(order)).isEqualTo("HELD");
        assertLevels("BUSY-1", 5, 2);
        reservations.release(order);
        assertLevels("BUSY-1", 5, 0);
    }

    @Test
    void aSweepThatWaitsTooLongLeavesTheHoldToItsNextRun() throws Exception {
        receive("BUSY-1", 5);
        reserve(order, line("BUSY-1", 2));
        expire(order);

        try (Connection gate = lockStockRows("BUSY-1")) {
            try {
                CompletableFuture.runAsync(this::sweep).get(5, TimeUnit.SECONDS);
            } finally {
                gate.rollback();
            }
        }
        assertThat(status(order)).isEqualTo("HELD");
        assertLevels("BUSY-1", 5, 2);

        sweep();
        assertThat(status(order)).isEqualTo("EXPIRED");
        assertLevels("BUSY-1", 5, 0);
    }
}
