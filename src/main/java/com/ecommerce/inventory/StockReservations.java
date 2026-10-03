package com.ecommerce.inventory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.util.Assert;

/**
 * Stock reservations for the saga, one per order (LLD §5.4–§5.6, ADR-009). Each operation runs in transactions of its
 * own, apart from any the caller has, and can be repeated safely. A stock row locked longer than the lock timeout
 * fails an operation with {@code CannotAcquireLockException}, after which it has changed nothing. An
 * {@link IllegalStateException} means the saga asked for a transition the reservation cannot make.
 */
public interface StockReservations {

    /**
     * Holds every line until now + {@code hold}, or none of them. A repeat returns the original outcome; repeating it
     * with other lines is an {@link IllegalArgumentException}.
     */
    StockReservation reserve(UUID orderId, List<Line> lines, Duration hold);

    /**
     * Payment succeeded: {@code HELD} to {@code COMMITTED}. An expired hold takes its stock again if it can;
     * {@code LOST} means it could not, or the reservation was released.
     */
    CommitResult commit(UUID orderId);

    /** Gives back the units of a {@code HELD} or {@code COMMITTED} reservation; nothing for one that holds none. */
    void release(UUID orderId);

    /** Handed over to the carrier: the committed units leave the warehouse. */
    void fulfill(UUID orderId);

    /** Returned to origin: the fulfilled units are back on hand. */
    void restockReturn(UUID orderId);

    /** One SKU of an order, as the catalog names it, and how many units of it. */
    record Line(String sku, int quantity) {

        public Line {
            Assert.hasText(sku, "sku must not be empty");
            Assert.isTrue(quantity >= 1, "quantity must be at least 1");
        }
    }
}
