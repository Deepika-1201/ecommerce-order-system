package com.ecommerce.fulfillment.domain;

import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.ShipmentStatus.Milestone;
import com.ecommerce.fulfillment.domain.ShipmentRepository.TrackingSource;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves shipments along their state machine, whatever moved them: a carrier scan or a warehouse mark (LLD §8.6), and
 * publishes each milestone once, as it is reached.
 */
@Component
class ShipmentUpdates {

    static final String AGGREGATE = "shipment";
    private static final String WAREHOUSE = "Origin warehouse";

    private static final Logger log = LoggerFactory.getLogger(ShipmentUpdates.class);

    private final ShipmentRepository repository;
    private final Messages messages;
    private final Clock clock;

    ShipmentUpdates(ShipmentRepository repository, Messages messages, Clock clock) {
        this.repository = repository;
        this.messages = messages;
        this.clock = clock;
    }

    /** Records a scan of the locked shipment and applies it if it is newer and reachable (ADR-024). */
    @Transactional(propagation = Propagation.MANDATORY)
    void applyScan(Shipment locked, String eventId, ShipmentStatus scanned, Instant occurredAt, String location) {
        boolean applies = Tracking.applies(locked.status(), locked.lastScanAt(), scanned, occurredAt);
        Instant now = clock.instant();
        repository.track(locked.id(), TrackingSource.CARRIER, eventId, scanned, location, occurredAt, applies, now);
        if (applies) {
            publishMilestones(locked.status(), repository.moved(locked.id(), scanned, occurredAt, now));
        } else if (locked.status() == ShipmentStatus.CANCELLED) {
            log.warn("Order {}'s cancelled shipment was scanned {} at {}: the parcel moved after its booking was "
                    + "cancelled", locked.orderId(), scanned, occurredAt);
        }
    }

    /** The warehouse moved the locked shipment on: packed, or handed over. */
    @Transactional(propagation = Propagation.MANDATORY)
    Shipment mark(Shipment locked, ShipmentStatus marked) {
        Instant now = clock.instant();
        Shipment moved = repository.moved(locked.id(), marked, null, now);
        repository.track(locked.id(), TrackingSource.WAREHOUSE, null, marked, WAREHOUSE, now, true, now);
        publishMilestones(locked.status(), moved);
        return moved;
    }

    void publish(Object message, Shipment shipment, Correlation correlation) {
        messages.publish(message, new Origin(AGGREGATE, shipment.orderId(), shipment.version()), correlation);
    }

    private void publishMilestones(ShipmentStatus before, Shipment after) {
        Correlation correlation = Correlation.start(after.orderId().toString());
        for (Milestone milestone : Tracking.reached(before, after.status())) {
            publish(event(milestone, after.orderId()), after, correlation);
        }
    }

    private static Object event(Milestone milestone, UUID orderId) {
        return switch (milestone) {
            case HANDED_OVER -> new ShipmentHandedOver(orderId);
            case DELIVERED -> new ShipmentDelivered(orderId);
            case RETURN_INITIATED -> new ShipmentReturnInitiated(orderId);
            case RETURNED_TO_ORIGIN -> new ShipmentReturnedToOrigin(orderId);
        };
    }
}
