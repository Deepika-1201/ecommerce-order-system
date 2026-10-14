package com.ecommerce.notifications.domain;

import com.ecommerce.customer.Customers;
import com.ecommerce.notifications.EmailDelivery;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.IntegrationInbox;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.platform.WorkerComponent;
import com.ecommerce.shared.Ids;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WorkerComponent
class NotificationService {

    static final String CONSUMER = "ecom-notifications";
    static final String SEND = "notifications.send-email";
    static final int MAX_ATTEMPTS = 20;

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final IntegrationInbox inbox;
    private final NotificationRepository notifications;
    private final TaskScheduler tasks;
    private final Customers customers;
    private final EmailDelivery email;

    NotificationService(IntegrationInbox inbox, NotificationRepository notifications, TaskScheduler tasks,
            Customers customers, EmailDelivery email) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.tasks = tasks;
        this.customers = customers;
        this.email = email;
    }

    void accept(IntegrationEvent event) {
        UUID orderId = UUID.fromString(event.data().path("order_id").asString());
        UUID customerId = UUID.fromString(event.data().path("customer_id").asString());
        inbox.receive(CONSUMER, event.messageId(), () -> {
            if (!notifications.advance(orderId, event.sequence())) {
                return;
            }
            String subject = switch (event.type()) {
                case "order.placed" -> "Order placed";
                case "order.confirmed" -> "Order confirmed";
                case "order.rejected" -> "Order could not be accepted";
                case "order.cancelled" -> "Order cancelled";
                case "order.shipped" -> "Order shipped";
                case "order.delivered" -> "Order delivered";
                case "order.delivery_failed" -> "Order returning to the warehouse";
                case "order.returned_to_origin" -> "Order returned to the warehouse";
                default -> throw new IllegalArgumentException("Not a supported order notification");
            };
            String body = subject + ".\nOrder: " + orderId + "\n";
            if (event.data().hasNonNull("refund_status")) {
                body += "Refund status: " + event.data().path("refund_status").asString() + "\n";
            }
            UUID id = Ids.newId();
            if (notifications.queue(id, event, orderId, customerId, subject, body)) {
                tasks.schedule(TaskRequest.of(SEND, new SendEmail(id)).dedupeKey(SEND + ":" + id)
                        .maxAttempts(MAX_ATTEMPTS).correlatedWith(orderId.toString()));
            }
        });
    }

    @HandlesTask(type = SEND)
    public void send(TaskExecution<SendEmail> task) {
        NotificationRepository.Pending pending = notifications.pending(task.payload().notificationId()).orElse(null);
        if (pending == null) {
            return;
        }
        Optional<String> recipient = customers.email(pending.customerId());
        if (recipient.isEmpty()) {
            notifications.record(pending.id(), "SKIPPED", task.attempt(), "missing_recipient");
            return;
        }
        try {
            email.send(pending.id(), recipient.orElseThrow(), pending.subject(), pending.body());
        } catch (EmailDelivery.Unavailable failure) {
            boolean exhausted = task.attempt() >= MAX_ATTEMPTS;
            notifications.record(pending.id(), exhausted ? "FAILED" : "PENDING", task.attempt(), "delivery_failed");
            if (exhausted) {
                log.error("notification_delivery_failed: notification {}, order {}", pending.id(), task.correlationId());
            }
            throw failure;
        }
        notifications.record(pending.id(), "SENT", task.attempt(), null);
    }

    record SendEmail(UUID notificationId) {
    }
}