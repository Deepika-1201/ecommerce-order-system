package com.ecommerce.inventory.domain;

import com.ecommerce.inventory.StockMessages.CommitReservation;
import com.ecommerce.inventory.StockMessages.FulfillReservation;
import com.ecommerce.inventory.StockMessages.ReleaseReservation;
import com.ecommerce.inventory.StockMessages.ReservationCommitted;
import com.ecommerce.inventory.StockMessages.ReservationLost;
import com.ecommerce.inventory.StockMessages.ReserveStock;
import com.ecommerce.inventory.StockMessages.RestockReturn;
import com.ecommerce.inventory.StockMessages.StockReservationFailed;
import com.ecommerce.inventory.StockMessages.StockReserved;
import com.ecommerce.inventory.StockReservation;
import com.ecommerce.inventory.StockReservations;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The saga's commands to Inventory (LLD §6.8). Each operation commits in transactions of its own before the reply is
 * published in the delivery transaction; if that fails, the redelivered command repeats the operation, which returns
 * the recorded outcome. A lock timeout fails the delivery, which is retried with backoff.
 */
@Component
class StockCommandHandlers {

    static final String REPLY_AGGREGATE = "reservation";

    private final StockReservations reservations;
    private final ReservationRepository repository;
    private final Messages messages;

    StockCommandHandlers(StockReservations reservations, ReservationRepository repository, Messages messages) {
        this.reservations = reservations;
        this.repository = repository;
        this.messages = messages;
    }

    @HandlesMessage(consumer = "inventory.reserve-stock")
    void reserveStock(IncomingMessage<ReserveStock> message) {
        ReserveStock command = message.payload();
        UUID orderId = command.orderId();
        reply(switch (reservations.reserve(orderId, command.lines(), command.hold())) {
            case StockReservation.Held held -> new StockReserved(orderId, held.reservationId(), held.expiresAt());
            case StockReservation.Rejected rejected ->
                    new StockReservationFailed(orderId, rejected.sku(), rejected.available());
        }, orderId, message);
    }

    @HandlesMessage(consumer = "inventory.commit-reservation")
    void commitReservation(IncomingMessage<CommitReservation> message) {
        UUID orderId = message.payload().orderId();
        reply(switch (reservations.commit(orderId)) {
            case COMMITTED -> new ReservationCommitted(orderId);
            case LOST -> new ReservationLost(orderId);
        }, orderId, message);
    }

    @HandlesMessage(consumer = "inventory.release-reservation")
    void releaseReservation(IncomingMessage<ReleaseReservation> message) {
        reservations.release(message.payload().orderId());
    }

    @HandlesMessage(consumer = "inventory.fulfill-reservation")
    void fulfillReservation(IncomingMessage<FulfillReservation> message) {
        reservations.fulfill(message.payload().orderId());
    }

    @HandlesMessage(consumer = "inventory.restock-return")
    void restockReturn(IncomingMessage<RestockReturn> message) {
        reservations.restockReturn(message.payload().orderId());
    }

    private void reply(Object reply, UUID orderId, IncomingMessage<?> command) {
        messages.publish(reply, new Origin(REPLY_AGGREGATE, orderId, repository.version(orderId)),
                Correlation.causedBy(command));
    }
}
