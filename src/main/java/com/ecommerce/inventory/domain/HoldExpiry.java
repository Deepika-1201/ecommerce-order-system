package com.ecommerce.inventory.domain;

import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Expires held reservations past their expiry, whole, and gives their units back (LLD §5.6): every minute, and on
 * demand for an order that comes up short. Each batch is one transaction, which locks its reservations first, then the
 * stock rows in lock order.
 */
@Component
class HoldExpiry {

    static final String SWEEP_TASK = "inventory.expire-holds";
    static final int BATCH = 100;
    private static final int MAX_SWEEP_BATCHES = 50;

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final StockLocks locks;
    private final Clock clock;

    HoldExpiry(ReservationRepository reservations, StockRepository stock, StockLocks locks, Clock clock) {
        this.reservations = reservations;
        this.stock = stock;
        this.locks = locks;
        this.clock = clock;
    }

    /** Oldest first; at most 50 batches per run, and the next run carries on. */
    @HandlesTask(type = SWEEP_TASK, every = "1m")
    void sweep(TaskExecution<Void> task) {
        int batches = 0;
        while (expireBatch(now -> reservations.expireDue(now, BATCH)) == BATCH && ++batches < MAX_SWEEP_BATCHES) {
            // Each batch commits on its own, so a backlog never holds locks for long.
        }
    }

    /** Expires every expired hold on these SKUs at the location; returns how many. */
    int reclaim(Collection<String> skus, String location) {
        int total = 0;
        int expired;
        do {
            expired = expireBatch(now -> reservations.expireDueHolding(skus, location, now, BATCH));
            total += expired;
        } while (expired == BATCH);
        return total;
    }

    private int expireBatch(Function<Instant, List<UUID>> claim) {
        return locks.inNewTransaction(status -> {
            Instant now = clock.instant();
            List<UUID> expired = claim.apply(now);
            reservations.unitsHeldBy(expired).stream()
                    .sorted(ReservedLine.LOCK_ORDER)
                    .forEach(units -> stock.giveBack(units, now));
            return expired.size();
        });
    }
}
