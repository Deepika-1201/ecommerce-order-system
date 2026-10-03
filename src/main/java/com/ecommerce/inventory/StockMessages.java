package com.ecommerce.inventory;

import com.ecommerce.platform.MessageType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The order saga's commands to Inventory and Inventory's replies (LLD §6.2). Replies come from aggregate
 * {@code reservation}, keyed by the order id, with the reservation's version.
 */
public final class StockMessages {

    private StockMessages() {
    }

    /** Hold every line for {@code hold}, or none (LLD §5.4): {@link StockReserved} or {@link StockReservationFailed}. */
    @MessageType(name = "inventory.reserve-stock")
    public record ReserveStock(UUID orderId, List<StockReservations.Line> lines, Duration hold) {

        public ReserveStock {
            lines = List.copyOf(lines);
        }
    }

    @MessageType(name = "inventory.stock-reserved")
    public record StockReserved(UUID orderId, UUID reservationId, Instant expiresAt) {
    }

    /** The first short line, in SKU order, and the units that were available for it. */
    @MessageType(name = "inventory.stock-reservation-failed")
    public record StockReservationFailed(UUID orderId, String sku, long available) {
    }

    /** Payment succeeded. Answered by {@link ReservationCommitted} or {@link ReservationLost}. */
    @MessageType(name = "inventory.commit-reservation")
    public record CommitReservation(UUID orderId) {
    }

    @MessageType(name = "inventory.reservation-committed")
    public record ReservationCommitted(UUID orderId) {
    }

    /** The hold expired and its stock could not be taken again, or it was released. */
    @MessageType(name = "inventory.reservation-lost")
    public record ReservationLost(UUID orderId) {
    }

    /** Give back whatever the order holds. No reply. */
    @MessageType(name = "inventory.release-reservation")
    public record ReleaseReservation(UUID orderId) {
    }

    /** Handed over to the carrier: the committed units leave the warehouse. No reply. */
    @MessageType(name = "inventory.fulfill-reservation")
    public record FulfillReservation(UUID orderId) {
    }

    /** Returned to origin: the units are back on hand. No reply. */
    @MessageType(name = "inventory.restock-return")
    public record RestockReturn(UUID orderId) {
    }
}
