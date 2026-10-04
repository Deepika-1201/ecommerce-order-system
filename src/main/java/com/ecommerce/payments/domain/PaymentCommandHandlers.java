package com.ecommerce.payments.domain;

import com.ecommerce.payments.PaymentMessages.CancelPayment;
import com.ecommerce.payments.PaymentMessages.CheckPayment;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentCreationFailed;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.gateway.GatewayPayment;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The saga's commands to Payments (LLD §7.5). Handlers run in the delivery transaction and never call the gateway:
 * they record the request and schedule the task that does, or answer from the record when it already knows.
 */
@Component
class PaymentCommandHandlers {

    private final PaymentRepository repository;
    private final PaymentUpdates updates;
    private final TaskScheduler tasks;
    private final Clock clock;

    PaymentCommandHandlers(PaymentRepository repository, PaymentUpdates updates, TaskScheduler tasks, Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.tasks = tasks;
        this.clock = clock;
    }

    @HandlesMessage(consumer = "payments.create-payment")
    void createPayment(IncomingMessage<CreatePayment> message) {
        UUID orderId = message.payload().orderId();
        repository.insertIfAbsent(Ids.newId(), message.payload(), clock.instant());
        PaymentRecord record = repository.lock(orderId).orElseThrow();
        switch (record.creation()) {
            case CREATED -> updates.publish(new PaymentCreated(orderId, record.id(), record.checkoutUrl()), record,
                    Correlation.causedBy(message));
            case FAILED -> updates.publish(new PaymentCreationFailed(orderId), record, Correlation.causedBy(message));
            case CREATING -> tasks.schedule(PaymentTasks.create(orderId, message.messageId(),
                    message.correlationId()));
        }
    }

    /** Cancels the payment, and keeps asking while an attempt is in flight (S6). */
    @HandlesMessage(consumer = "payments.cancel-payment")
    void cancelPayment(IncomingMessage<CancelPayment> message) {
        UUID orderId = message.payload().orderId();
        existing(orderId);
        repository.requestCancel(orderId, clock.instant());
        tasks.schedule(PaymentTasks.cancel(orderId, message.messageId(), message.correlationId()));
    }

    @HandlesMessage(consumer = "payments.check-payment")
    void checkPayment(IncomingMessage<CheckPayment> message) {
        UUID orderId = message.payload().orderId();
        existing(orderId);
        tasks.schedule(PaymentTasks.check(orderId, message.messageId(), message.correlationId()));
    }

    /** One refund per order and reason, of a successful payment; its task answers, whether or not it is new. */
    @HandlesMessage(consumer = "payments.refund-payment")
    void refundPayment(IncomingMessage<RefundPayment> message) {
        RefundPayment command = message.payload();
        UUID orderId = command.orderId();
        PaymentRecord payment = existing(orderId);
        if (payment.status() != GatewayPayment.Status.SUCCEEDED) {
            throw new IllegalStateException("Order " + orderId + " cannot be refunded: its payment is "
                    + payment.status());
        }
        repository.insertRefundIfAbsent(Ids.newId(), orderId, command.reason(), command.amountPaise(),
                clock.instant());
        tasks.schedule(PaymentTasks.refund(orderId, command.reason(), message.messageId(), message.correlationId()));
    }

    /** The order's record, locked; a command before {@code CreatePayment} fails its delivery, which is retried. */
    private PaymentRecord existing(UUID orderId) {
        return repository.lock(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no payment"));
    }
}
