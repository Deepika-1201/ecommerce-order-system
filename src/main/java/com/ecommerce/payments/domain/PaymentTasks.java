package com.ecommerce.payments.domain;

import com.ecommerce.payments.PaymentMessages.PaymentCancelRefused;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentCreationFailed;
import com.ecommerce.payments.PaymentMessages.PaymentPending;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.domain.PaymentUpdates.Applied;
import com.ecommerce.payments.gateway.GatewayEvents;
import com.ecommerce.payments.gateway.GatewayEvents.GatewayEvent;
import com.ecommerce.payments.gateway.GatewayPayment;
import com.ecommerce.payments.gateway.GatewayRefund;
import com.ecommerce.payments.gateway.GatewayRefusedException;
import com.ecommerce.payments.gateway.PaymentGateway;
import com.ecommerce.payments.gateway.PaymentGateway.CheckoutSession;
import com.ecommerce.payments.gateway.PaymentGateway.NewPayment;
import com.ecommerce.payments.gateway.PaymentGateway.NewRefund;
import com.ecommerce.payments.gateway.PaymentsProperties;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.WebhookInbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tasks that call the gateway (LLD §7.5): outside any transaction, then a short transaction that locks the record,
 * applies what the gateway said and publishes the answer. {@link com.ecommerce.payments.gateway.GatewayUnavailableException}
 * is left to the task runner, which retries with backoff and the same idempotency key.
 */
@Component
class PaymentTasks {

    static final String CREATE = "payments.create-payment";
    static final String CANCEL = "payments.cancel-payment";
    static final String CHECK = "payments.check-payment";
    static final String REFUND = "payments.refund-payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentTasks.class);
    private static final Duration SHORTEST_PAYMENT = Duration.ofMinutes(1);

    /** The order whose payment a task is for, and the command that asked, for the answer's causation. */
    record PaymentTask(UUID orderId, UUID causationId) {
    }

    record RefundTask(UUID orderId, RefundReason reason, UUID causationId) {
    }

    private final PaymentRepository repository;
    private final PaymentUpdates updates;
    private final PaymentGateway gateway;
    private final GatewayEvents events;
    private final WebhookInbox inbox;
    private final PaymentsProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PaymentTasks(PaymentRepository repository, PaymentUpdates updates, PaymentGateway gateway, GatewayEvents events,
                 WebhookInbox inbox, PaymentsProperties properties, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.gateway = gateway;
        this.events = events;
        this.inbox = inbox;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    static TaskRequest create(UUID orderId, UUID causationId, String correlationId) {
        return task(CREATE, orderId, causationId, correlationId);
    }

    static TaskRequest cancel(UUID orderId, UUID causationId, String correlationId) {
        return task(CANCEL, orderId, causationId, correlationId);
    }

    static TaskRequest check(UUID orderId, UUID causationId, String correlationId) {
        return task(CHECK, orderId, causationId, correlationId);
    }

    static TaskRequest refund(UUID orderId, RefundReason reason, UUID causationId, String correlationId) {
        return TaskRequest.of(REFUND, new RefundTask(orderId, reason, causationId))
                .dedupeKey(REFUND + ":" + orderId + ":" + reason)
                .correlatedWith(correlationId == null ? orderId.toString() : correlationId);
    }

    private static TaskRequest task(String type, UUID orderId, UUID causationId, String correlationId) {
        return TaskRequest.of(type, new PaymentTask(orderId, causationId))
                .dedupeKey(type + ":" + orderId)
                .correlatedWith(correlationId == null ? orderId.toString() : correlationId);
    }

    /**
     * Creates the payment, then its checkout session, saving each; within the creation budget (S15), or while the
     * payment would still last a minute. A payment left without a session is cancelled, best effort.
     */
    @HandlesTask(type = CREATE)
    void createPayment(TaskExecution<PaymentTask> task) {
        UUID orderId = task.payload().orderId();
        PaymentRecord record = repository.find(orderId).orElseThrow();
        if (record.creation() != PaymentRecord.Creation.CREATING) {
            return;
        }
        Instant now = clock.instant();
        if (now.isAfter(record.createdAt().plus(properties.creationBudget()))
                || Duration.between(now, record.expiresAt()).compareTo(SHORTEST_PAYMENT) < 0) {
            giveUp(record, correlation(task), "its creation budget is spent");
            return;
        }
        try {
            if (record.gatewayPaymentId() == null) {
                // The same request on every retry: the gateway refuses another body under the same key. So the
                // payment lasts as long as the window counted from the record, and ends at most the budget later.
                Duration payable = Duration.between(record.createdAt(), record.expiresAt());
                GatewayPayment payment = gateway.createPayment(new NewPayment(orderId, record.customerId(),
                        record.amountPaise(), payable), orderId + ":payment");
                record = inTransaction(() -> repository.gatewayCreated(orderId, payment, clock.instant()));
            }
            CheckoutSession session = gateway.createCheckoutSession(record.gatewayPaymentId(),
                    properties.returnUrl(orderId), orderId + ":checkout");
            transactions.executeWithoutResult(status -> {
                PaymentRecord locked = repository.lock(orderId).orElseThrow();
                if (locked.creation() == PaymentRecord.Creation.CREATING) {
                    PaymentRecord created = repository.created(orderId, session.url(), clock.instant());
                    updates.publish(new PaymentCreated(orderId, created.id(), session.url()), created,
                            correlation(task));
                }
            });
        } catch (GatewayRefusedException e) {
            giveUp(repository.find(orderId).orElseThrow(), correlation(task), "the gateway refused it: " + e.code());
        }
    }

    private void giveUp(PaymentRecord record, Correlation correlation, String why) {
        UUID orderId = record.orderId();
        log.warn("The payment of order {} was not created: {}", orderId, why);
        transactions.executeWithoutResult(status -> {
            PaymentRecord locked = repository.lock(orderId).orElseThrow();
            if (locked.creation() == PaymentRecord.Creation.CREATING) {
                updates.publish(new PaymentCreationFailed(orderId),
                        repository.creationFailed(orderId, clock.instant()), correlation);
            }
        });
        if (record.gatewayPaymentId() != null) {
            try {
                gateway.cancelPayment(record.gatewayPaymentId(), orderId + ":cancel:abandoned");
            } catch (RuntimeException e) {
                log.warn("The payment of order {}, left without a checkout session, was not cancelled: {}", orderId,
                        e.getMessage());
            }
        }
    }

    /**
     * Cancels the payment. Refused: reads it, and answers that it cannot be cancelled while an attempt is in flight;
     * the cancel is sent again if the attempt fails (S6). A final payment is answered with its outcome.
     */
    @HandlesTask(type = CANCEL)
    void cancelPayment(TaskExecution<PaymentTask> task) {
        UUID orderId = task.payload().orderId();
        PaymentRecord record = repository.find(orderId).orElseThrow();
        if (record.gatewayPaymentId() == null) {
            throw new IllegalStateException("The payment of order " + orderId + " is not created yet");
        }
        boolean refused = false;
        GatewayPayment payment;
        try {
            payment = gateway.cancelPayment(record.gatewayPaymentId(), orderId + ":cancel:" + record.gatewayVersion());
        } catch (GatewayRefusedException e) {
            if (!"payment_invalid_state".equals(e.code())) {
                log.error("The gateway refused to cancel the payment of order {}: {}", orderId, e.code());
                return;
            }
            refused = true;
            payment = gateway.payment(record.gatewayPaymentId());
        }
        GatewayPayment reported = payment;
        boolean cancellableAgain = refused && reported.status().canBeCancelled();
        transactions.executeWithoutResult(status -> {
            Applied applied = updates.apply(repository.lock(orderId).orElseThrow(), reported, correlation(task));
            PaymentRecord current = applied.record();
            if (current.isFinal()) {
                if (!applied.published()) {
                    updates.publish(PaymentUpdates.outcome(current), current, correlation(task));
                }
            } else if (!cancellableAgain) {
                updates.publish(new PaymentCancelRefused(orderId, current.id()), current, correlation(task));
            }
        });
        if (cancellableAgain) {
            throw new IllegalStateException("The payment of order " + orderId + " became cancellable while it was "
                    + "being cancelled; trying again");
        }
    }

    /** Reads the payment and applies it: its outcome if final, else pending. */
    @HandlesTask(type = CHECK)
    void checkPayment(TaskExecution<PaymentTask> task) {
        UUID orderId = task.payload().orderId();
        PaymentRecord record = repository.find(orderId).orElseThrow();
        if (record.gatewayPaymentId() == null) {
            transactions.executeWithoutResult(status -> updates.publish(new PaymentPending(orderId, record.id()),
                    repository.lock(orderId).orElseThrow(), correlation(task)));
            return;
        }
        GatewayPayment payment;
        try {
            payment = gateway.payment(record.gatewayPaymentId());
        } catch (GatewayRefusedException e) {
            log.error("The gateway does not know the payment of order {} ({}): {}", orderId,
                    record.gatewayPaymentId(), e.code());
            return;
        }
        transactions.executeWithoutResult(status -> {
            Applied applied = updates.apply(repository.lock(orderId).orElseThrow(), payment, correlation(task));
            PaymentRecord current = applied.record();
            if (!current.isFinal()) {
                updates.publish(new PaymentPending(orderId, current.id()), current, correlation(task));
            } else if (!applied.published()) {
                updates.publish(PaymentUpdates.outcome(current), current, correlation(task));
            }
        });
    }

    /** Creates the refund at the gateway once, and answers that it was initiated; its end follows by event. */
    @HandlesTask(type = REFUND)
    void refundPayment(TaskExecution<RefundTask> task) {
        UUID orderId = task.payload().orderId();
        RefundReason reason = task.payload().reason();
        Correlation correlation = new Correlation(correlationId(task, orderId), task.payload().causationId());
        RefundRecord refund = repository.refund(orderId, reason).orElseThrow();
        GatewayRefund created = null;
        if (refund.gatewayRefundId() == null) {
            PaymentRecord payment = repository.find(orderId).orElseThrow();
            try {
                created = gateway.createRefund(payment.gatewayPaymentId(), new NewRefund(refund.amountPaise(),
                                reason.name().toLowerCase(Locale.ROOT), orderId + ":" + reason),
                        orderId + ":refund:" + reason);
            } catch (GatewayRefusedException e) {
                log.error("The gateway refused the {} refund of order {}: {}", reason, orderId, e.code());
                return;
            }
        }
        GatewayRefund answer = created;
        transactions.executeWithoutResult(status -> {
            PaymentRecord payment = repository.lock(orderId).orElseThrow();
            RefundRecord current = repository.refund(orderId, reason).orElseThrow();
            if (answer != null) {
                current = updates.update(current, answer, correlation);
            }
            updates.publish(new RefundInitiated(orderId, current.id(), reason, current.amountPaise()),
                    repository.find(orderId).orElse(payment), correlation);
        });
    }

    /** Applies an event from the inbox, then marks it processed (LLD §7.7). */
    @HandlesTask(type = GatewayEvents.APPLY_TASK)
    void applyEvent(TaskExecution<ReceivedWebhook> task) {
        ReceivedWebhook webhook = task.payload();
        GatewayEvent event = events.read(webhook);
        transactions.executeWithoutResult(status -> {
            switch (event) {
                case GatewayEvent.PaymentChanged(GatewayPayment payment) ->
                        repository.lockByGatewayId(payment.id()).ifPresentOrElse(
                                record -> updates.apply(record, payment, Correlation.start(record.orderId().toString())),
                                () -> log.warn("Event {} is about payment {}, which this system did not create",
                                        webhook.eventId(), payment.id()));
                case GatewayEvent.RefundChanged(GatewayRefund refund) -> updates.applyRefund(refund);
                case GatewayEvent.Ignored ignored -> log.info("Event {} ({}) needs nothing from this system",
                        webhook.eventId(), ignored.type());
            }
            inbox.markProcessed(webhook);
        });
    }

    private PaymentRecord inTransaction(Supplier<PaymentRecord> work) {
        return transactions.execute(status -> work.get());
    }

    private static Correlation correlation(TaskExecution<PaymentTask> task) {
        return new Correlation(correlationId(task, task.payload().orderId()), task.payload().causationId());
    }

    private static String correlationId(TaskExecution<?> task, UUID orderId) {
        return task.correlationId() == null ? orderId.toString() : task.correlationId();
    }
}
