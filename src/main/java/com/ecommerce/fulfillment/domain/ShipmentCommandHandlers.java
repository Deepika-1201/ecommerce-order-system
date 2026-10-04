package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The saga's commands to Fulfillment (LLD §8.5). Handlers run in the delivery transaction and never call the carrier:
 * they record the request and schedule the task that does, or answer from the shipment when it already decides.
 */
@Component
class ShipmentCommandHandlers {

    private final ShipmentRepository repository;
    private final ShipmentUpdates updates;
    private final TaskScheduler tasks;
    private final FulfillmentProperties properties;
    private final Clock clock;

    ShipmentCommandHandlers(ShipmentRepository repository, ShipmentUpdates updates, TaskScheduler tasks,
                            FulfillmentProperties properties, Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.tasks = tasks;
        this.properties = properties;
        this.clock = clock;
    }

    /** One shipment per order, booked by a task; a shipment cancelled before this arrived stays cancelled. */
    @HandlesMessage(consumer = "fulfillment.create-shipment")
    void createShipment(IncomingMessage<CreateShipment> message) {
        UUID orderId = message.payload().orderId();
        Instant now = clock.instant();
        repository.insertIfAbsent(Ids.newId(), message.payload(), now.plus(properties.bookingBudget()), now);
        if (repository.lock(orderId).orElseThrow().status() == ShipmentStatus.PENDING_BOOKING) {
            tasks.schedule(ShipmentTasks.book(orderId, message.correlationId()));
        }
    }

    /** Cancelled at once before the booking; at the carrier while booked or packed; refused once handed over. */
    @HandlesMessage(consumer = "fulfillment.cancel-shipment")
    void cancelShipment(IncomingMessage<CancelShipment> message) {
        UUID orderId = message.payload().orderId();
        Instant now = clock.instant();
        repository.insertCancelled(Ids.newId(), orderId, now);
        Shipment shipment = repository.lock(orderId).orElseThrow();
        Correlation correlation = Correlation.causedBy(message);
        switch (shipment.status()) {
            case PENDING_BOOKING, BOOKING_FAILED ->
                    updates.publish(new ShipmentCancelled(orderId), repository.cancelled(shipment.id(), now), correlation);
            case CANCELLED -> updates.publish(new ShipmentCancelled(orderId), shipment, correlation);
            case BOOKED, PACKED -> {
                if (!shipment.cancelRequested()) {
                    repository.requestCancel(shipment.id(), now);
                }
                tasks.schedule(ShipmentTasks.cancel(orderId, message.messageId(), message.correlationId()));
            }
            case HANDED_OVER, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_ATTEMPT_FAILED, DELIVERED, RTO_IN_TRANSIT,
                    RTO_DELIVERED -> updates.publish(new ShipmentCancelRefused(orderId), shipment, correlation);
        }
    }
}
