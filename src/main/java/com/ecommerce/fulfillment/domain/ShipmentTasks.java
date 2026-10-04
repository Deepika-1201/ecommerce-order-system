package com.ecommerce.fulfillment.domain;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.customer.Customers;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.carrier.Carrier;
import com.ecommerce.fulfillment.carrier.CarrierEvents;
import com.ecommerce.fulfillment.carrier.CarrierEvents.CarrierEvent;
import com.ecommerce.fulfillment.carrier.CarrierRefusedException;
import com.ecommerce.fulfillment.carrier.CarrierUnavailableException;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.WebhookInbox;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tasks that call the carrier (LLD §8.5): outside any transaction, then a short transaction that locks the
 * shipment and applies what the carrier said. {@link com.ecommerce.fulfillment.carrier.CarrierUnavailableException}
 * is left to the task runner, which retries with backoff and the same reference.
 */
@Component
class ShipmentTasks {

    static final String BOOK = "fulfillment.book-shipment";
    static final String CANCEL = "fulfillment.cancel-shipment";
    /**
     * Far more than the budget uses: at the runner's backoff, 10 s doubling to an hour with jitter down to half, 100
     * attempts span at least 45 hours. Should they not, the last one fails the booking rather than dying silently.
     */
    static final int BOOKING_ATTEMPTS = 100;

    private static final Logger log = LoggerFactory.getLogger(ShipmentTasks.class);

    /** The order whose shipment a task is for, and the command that asked, for the answer's causation. */
    record ShipmentTask(UUID orderId, UUID causationId) {
    }

    private final ShipmentRepository repository;
    private final ShipmentUpdates updates;
    private final Carrier carrier;
    private final CarrierEvents events;
    private final WebhookInbox inbox;
    private final Customers customers;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ShipmentTasks(ShipmentRepository repository, ShipmentUpdates updates, Carrier carrier, CarrierEvents events,
                  WebhookInbox inbox, Customers customers, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.carrier = carrier;
        this.events = events;
        this.inbox = inbox;
        this.customers = customers;
        this.transactions = transactions;
        this.clock = clock;
    }

    static TaskRequest book(UUID orderId, String correlationId) {
        return TaskRequest.of(BOOK, new ShipmentTask(orderId, null))
                .dedupeKey(BOOK + ":" + orderId)
                .maxAttempts(BOOKING_ATTEMPTS)
                .correlatedWith(correlationId == null ? orderId.toString() : correlationId);
    }

    static TaskRequest cancel(UUID orderId, UUID causationId, String correlationId) {
        return TaskRequest.of(CANCEL, new ShipmentTask(orderId, causationId))
                .dedupeKey(CANCEL + ":" + orderId)
                .correlatedWith(correlationId == null ? orderId.toString() : correlationId);
    }

    /**
     * Books the parcel with the shipment id as the reference, within the booking budget (S8). A refusal, or a budget
     * spent, fails the booking and raises the alert. A booking that completes after a cancellation is cancelled at
     * the carrier, best effort.
     */
    @HandlesTask(type = BOOK)
    void bookShipment(TaskExecution<ShipmentTask> task) {
        UUID orderId = task.payload().orderId();
        Shipment shipment = repository.find(orderId).orElseThrow();
        if (shipment.status() != ShipmentStatus.PENDING_BOOKING) {
            return;
        }
        if (clock.instant().isAfter(shipment.bookingDeadline())) {
            fail(orderId, "its booking budget is spent");
            return;
        }
        AddressSnapshot address = customers.addressSnapshot(shipment.deliveryAddressId())
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no delivery address "
                        + shipment.deliveryAddressId()));
        String awb;
        try {
            awb = carrier.book(new Carrier.Parcel(shipment.id(), address(address), repository.lines(shipment.id())));
        } catch (CarrierRefusedException e) {
            fail(orderId, "the carrier refused it: " + e.code());
            return;
        } catch (CarrierUnavailableException e) {
            if (task.attempt() < BOOKING_ATTEMPTS) {
                throw e;
            }
            fail(orderId, "the carrier stayed unavailable for " + BOOKING_ATTEMPTS + " attempts");
            return;
        }
        boolean cancelled = Boolean.TRUE.equals(transactions.execute(status -> {
            Shipment locked = repository.lock(orderId).orElseThrow();
            if (locked.status() == ShipmentStatus.PENDING_BOOKING) {
                repository.booked(locked.id(), carrier.name(), awb, clock.instant());
            }
            return locked.status() == ShipmentStatus.CANCELLED;
        }));
        if (cancelled) {
            try {
                carrier.cancel(awb);
                log.info("Order {}'s shipment was cancelled while it was being booked; booking {} is cancelled too",
                        orderId, awb);
            } catch (RuntimeException e) {
                log.warn("Order {}'s shipment was cancelled while it was being booked; booking {} could not be "
                        + "cancelled: {}", orderId, awb, e.getMessage());
            }
        }
    }

    /** Cancels the booking at the carrier, then answers as the shipment then stands. */
    @HandlesTask(type = CANCEL)
    void cancelShipment(TaskExecution<ShipmentTask> task) {
        UUID orderId = task.payload().orderId();
        Shipment shipment = repository.find(orderId).orElseThrow();
        boolean pickedUp = false;
        if (shipment.cancelRequested()) {
            try {
                carrier.cancel(shipment.awb());
            } catch (CarrierRefusedException e) {
                if (!"picked_up".equals(e.code())) {
                    throw e;
                }
                pickedUp = true;
            }
        }
        boolean refused = pickedUp;
        Correlation correlation = new Correlation(correlationId(task), task.payload().causationId());
        transactions.executeWithoutResult(status -> {
            Shipment locked = repository.lock(orderId).orElseThrow();
            if (locked.status() == ShipmentStatus.CANCELLED) {
                updates.publish(new ShipmentCancelled(orderId), locked, correlation);
            } else if (locked.cancelRequested() && !refused) {
                updates.publish(new ShipmentCancelled(orderId), repository.cancelled(locked.id(), clock.instant()),
                        correlation);
            } else {
                Shipment current = locked.cancelRequested() ? repository.cancelRefused(locked.id(), clock.instant())
                        : locked;
                updates.publish(new ShipmentCancelRefused(orderId), current, correlation);
            }
        });
    }

    /** Applies an event from the inbox, then marks it processed (LLD §8.8). */
    @HandlesTask(type = CarrierEvents.APPLY_TASK)
    void applyEvent(TaskExecution<ReceivedWebhook> task) {
        ReceivedWebhook webhook = task.payload();
        CarrierEvent event = events.read(webhook);
        transactions.executeWithoutResult(status -> {
            switch (event) {
                case CarrierEvent.Scanned scanned -> apply(scanned);
                case CarrierEvent.Ignored ignored -> log.info("Carrier event {} ({}) needs nothing from this system",
                        webhook.eventId(), ignored.type());
            }
            inbox.markProcessed(webhook);
        });
    }

    private void apply(CarrierEvent.Scanned scanned) {
        ShipmentStatus status = scanned.scan() == null ? null : ShipmentStatus.ofScan(scanned.scan()).orElse(null);
        if (status == null || scanned.reference() == null || scanned.occurredAt() == null) {
            log.warn("Carrier event {} is not a scan this system reads: {} of {} at {}", scanned.eventId(),
                    scanned.scan(), scanned.reference(), scanned.occurredAt());
            return;
        }
        repository.lockById(scanned.reference()).ifPresentOrElse(
                shipment -> updates.applyScan(shipment, scanned.eventId(), status, scanned.occurredAt(),
                        scanned.location()),
                () -> log.warn("Carrier event {} is about shipment {}, which this system does not have",
                        scanned.eventId(), scanned.reference()));
    }

    private void fail(UUID orderId, String why) {
        transactions.executeWithoutResult(status -> {
            Shipment locked = repository.lock(orderId).orElseThrow();
            if (locked.status() == ShipmentStatus.PENDING_BOOKING) {
                repository.bookingFailed(locked.id(), why, clock.instant());
                log.error("shipment_booking_failed: order {}, shipment {}: {}", orderId, locked.id(), why);
            }
        });
    }

    private static Carrier.Address address(AddressSnapshot snapshot) {
        return new Carrier.Address(snapshot.recipientName(), snapshot.phone(), snapshot.line1(), snapshot.line2(),
                snapshot.landmark(), snapshot.city(), snapshot.state().code(), snapshot.pinCode());
    }

    private static String correlationId(TaskExecution<ShipmentTask> task) {
        return task.correlationId() == null ? task.payload().orderId().toString() : task.correlationId();
    }
}
