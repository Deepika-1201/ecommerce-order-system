package com.ecommerce.payments.domain;

import com.ecommerce.payments.PaymentMessages.PaymentCancelled;
import com.ecommerce.payments.PaymentMessages.PaymentExpired;
import com.ecommerce.payments.PaymentMessages.PaymentFailed;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundFailed;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.PaymentMessages.RefundSucceeded;
import com.ecommerce.payments.gateway.GatewayPayment;
import com.ecommerce.payments.gateway.GatewayRefund;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the gateway's state, whatever brought it: a webhook, a poll or the answer to a command (LLD §7.6). Only a
 * newer gateway version changes a record, and entering a final status publishes it, once.
 */
@Component
class PaymentUpdates {

    static final String AGGREGATE = "payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentUpdates.class);

    private final PaymentRepository repository;
    private final Messages messages;
    private final TaskScheduler tasks;
    private final Clock clock;

    PaymentUpdates(PaymentRepository repository, Messages messages, TaskScheduler tasks, Clock clock) {
        this.repository = repository;
        this.messages = messages;
        this.tasks = tasks;
        this.clock = clock;
    }

    /** The record after applying the payment, and whether its outcome was published. */
    record Applied(PaymentRecord record, boolean published) {
    }

    /** Applies the payment to the locked record. */
    @Transactional(propagation = Propagation.MANDATORY)
    Applied apply(PaymentRecord locked, GatewayPayment payment, Correlation correlation) {
        if (payment.version() <= locked.gatewayVersion()) {
            return new Applied(locked, false);
        }
        PaymentRecord record = repository.applyStatus(locked.orderId(), payment.status(), payment.version(),
                clock.instant());
        boolean entersFinal = payment.status().isFinal() && locked.status() != payment.status();
        if (entersFinal) {
            publish(outcome(record), record, correlation);
        }
        if (record.cancelRequested() && payment.status().canBeCancelled()) {
            tasks.schedule(PaymentTasks.cancel(record.orderId(), null, correlation.correlationId()));
        }
        return new Applied(record, entersFinal);
    }

    /**
     * Applies a refund as the gateway reports it, under its payment record's lock. The gateway's own refunds are
     * recorded when first seen; a refund with a reason publishes its end.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void applyRefund(GatewayRefund refund) {
        Optional<PaymentRecord> payment = repository.lockByGatewayId(refund.paymentId());
        if (payment.isEmpty()) {
            log.warn("Refund {} is for payment {}, which this system did not create", refund.id(), refund.paymentId());
            return;
        }
        UUID orderId = payment.get().orderId();
        Optional<RefundRecord> known = repository.refundByGatewayId(refund.id())
                .or(() -> sagaRefund(orderId, refund.merchantRefundId()));
        RefundRecord record;
        if (known.isPresent()) {
            record = known.get();
        } else if (refund.initiatedBy() == GatewayRefund.Initiator.MERCHANT) {
            log.warn("Refund {} of order {} was not requested by this system", refund.id(), orderId);
            return;
        } else {
            RefundReason reason = refund.initiatedBy() == GatewayRefund.Initiator.SYSTEM_LATE_SUCCESS
                    ? RefundReason.LATE_SUCCESS : null;
            record = repository.insertGatewayRefund(Ids.newId(), orderId, refund, reason, clock.instant());
        }
        update(record, refund, Correlation.start(orderId.toString()));
    }

    /** Applies the refund to its record, if newer; publishes its end if it has a reason. Returns the record. */
    @Transactional(propagation = Propagation.MANDATORY)
    RefundRecord update(RefundRecord record, GatewayRefund refund, Correlation correlation) {
        if (refund.version() <= record.gatewayVersion()) {
            return record;
        }
        RefundRecord.Status status = RefundRecord.Status.of(refund.status());
        RefundRecord updated = repository.updateRefund(record.id(), refund.id(), status, refund.version(),
                clock.instant());
        if (status.isFinal() && record.status() != status && updated.reason() != null) {
            long sequence = repository.countChange(updated.orderId(), clock.instant());
            Object event = status == RefundRecord.Status.SUCCEEDED
                    ? new RefundSucceeded(updated.orderId(), updated.id(), updated.reason(), updated.amountPaise())
                    : new RefundFailed(updated.orderId(), updated.id(), updated.reason(), updated.amountPaise());
            messages.publish(event, new Origin(AGGREGATE, updated.orderId(), sequence), correlation);
        }
        return updated;
    }

    /** The event that reports a final payment. */
    static Object outcome(PaymentRecord record) {
        UUID orderId = record.orderId();
        return switch (record.status()) {
            case SUCCEEDED -> new PaymentSucceeded(orderId, record.id(), record.amountPaise());
            case FAILED -> new PaymentFailed(orderId, record.id());
            case CANCELLED -> new PaymentCancelled(orderId, record.id());
            case EXPIRED -> new PaymentExpired(orderId, record.id());
            case REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING, AUTHORIZED -> throw new IllegalStateException(
                    "The payment of order " + orderId + " has no outcome yet: " + record.status());
        };
    }

    void publish(Object message, PaymentRecord record, Correlation correlation) {
        messages.publish(message, new Origin(AGGREGATE, record.orderId(), record.version()), correlation);
    }

    /** The saga's refund that the gateway names by our {@code merchant_refund_id}: {@code {order}:{reason}}. */
    private Optional<RefundRecord> sagaRefund(UUID orderId, String merchantRefundId) {
        String prefix = orderId + ":";
        if (merchantRefundId == null || !merchantRefundId.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return repository.refund(orderId, RefundReason.valueOf(merchantRefundId.substring(prefix.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
