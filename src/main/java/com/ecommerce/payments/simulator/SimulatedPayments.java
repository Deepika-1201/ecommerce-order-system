package com.ecommerce.payments.simulator;

import com.ecommerce.payments.PaymentMessages.CancelPayment;
import com.ecommerce.payments.PaymentMessages.CheckPayment;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCancelRefused;
import com.ecommerce.payments.PaymentMessages.PaymentCancelled;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentCreationFailed;
import com.ecommerce.payments.PaymentMessages.PaymentExpired;
import com.ecommerce.payments.PaymentMessages.PaymentFailed;
import com.ecommerce.payments.PaymentMessages.PaymentPending;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentSimulator;
import com.ecommerce.payments.simulator.SimulatedPayment.Status;
import com.ecommerce.payments.simulator.SimulatedPaymentRepository.Refund;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payments as a simulator until phase 7 (ADR-022, LLD §6.8): it answers the saga's commands at once, idempotently per
 * order, and tests decide each payment's fate through {@link PaymentSimulator}.
 */
@Component
class SimulatedPayments implements PaymentSimulator {

    static final String AGGREGATE = "payment";
    static final String CHECKOUT_URL = "https://checkout.simulator.invalid/pay/";

    private final SimulatedPaymentRepository payments;
    private final Messages messages;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SimulatedPayments(SimulatedPaymentRepository payments, Messages messages, TransactionTemplate transactions,
            Clock clock) {
        this.payments = payments;
        this.messages = messages;
        this.transactions = transactions;
        this.clock = clock;
    }

    @HandlesMessage(consumer = "payments.create-payment")
    void createPayment(IncomingMessage<CreatePayment> message) {
        CreatePayment command = message.payload();
        UUID orderId = command.orderId();
        payments.createIfAbsent(orderId, Ids.newId(), command.amountPaise(), command.expiresAt(), clock.instant());
        SimulatedPayment payment = payments.lock(orderId).orElseThrow();
        UUID paymentId = payment.paymentId();
        reply(payment.status() == Status.CREATION_REFUSED
                ? new PaymentCreationFailed(orderId)
                : new PaymentCreated(orderId, paymentId, CHECKOUT_URL + paymentId), payment, message);
    }

    /** Cancelled before an attempt; refused during one or after a success; a failure or expiry is answered as such. */
    @HandlesMessage(consumer = "payments.cancel-payment")
    void cancelPayment(IncomingMessage<CancelPayment> message) {
        UUID orderId = message.payload().orderId();
        SimulatedPayment payment = created(orderId);
        if (payment.status() == Status.REQUIRES_PAYMENT) {
            payment = payments.setStatus(orderId, Status.CANCELLED, clock.instant());
        }
        UUID paymentId = payment.paymentId();
        reply(switch (payment.status()) {
            case PROCESSING, SUCCEEDED -> new PaymentCancelRefused(orderId, paymentId);
            default -> outcome(payment);
        }, payment, message);
    }

    /** The outcome, expiring a payment whose time is up; pending while it can still be paid. */
    @HandlesMessage(consumer = "payments.check-payment")
    void checkPayment(IncomingMessage<CheckPayment> message) {
        UUID orderId = message.payload().orderId();
        Instant now = clock.instant();
        SimulatedPayment payment = created(orderId);
        if (payment.status() == Status.REQUIRES_PAYMENT && !payment.expiresAt().isAfter(now)) {
            payment = payments.setStatus(orderId, Status.EXPIRED, now);
        }
        reply(switch (payment.status()) {
            case REQUIRES_PAYMENT, PROCESSING -> new PaymentPending(orderId, payment.paymentId());
            default -> outcome(payment);
        }, payment, message);
    }

    /** One refund per order and reason; a repeat answers with the first. Only a successful payment is refunded. */
    @HandlesMessage(consumer = "payments.refund-payment")
    void refundPayment(IncomingMessage<RefundPayment> message) {
        RefundPayment command = message.payload();
        UUID orderId = command.orderId();
        SimulatedPayment payment = created(orderId);
        if (payment.status() != Status.SUCCEEDED) {
            throw new IllegalStateException("Order " + orderId + " cannot be refunded: its payment is "
                    + payment.status());
        }
        Refund refund = payments.refund(orderId, command.reason(), Ids.newId(), command.amountPaise(),
                clock.instant());
        reply(new RefundInitiated(orderId, refund.refundId(), command.reason(), refund.amountPaise()), payment,
                message);
    }

    @Override
    public void refuseCreation(UUID orderId) {
        transactions.executeWithoutResult(status -> {
            if (!payments.refuse(orderId, clock.instant())
                    && payments.lock(orderId).orElseThrow().status() != Status.CREATION_REFUSED) {
                throw new IllegalStateException("The payment of order " + orderId + " was already created");
            }
        });
    }

    @Override
    public void startAttempt(UUID orderId) {
        change(orderId, Set.of(Status.REQUIRES_PAYMENT), Status.PROCESSING, null);
    }

    @Override
    public void succeed(UUID orderId) {
        change(orderId, Set.of(Status.REQUIRES_PAYMENT, Status.PROCESSING, Status.EXPIRED), Status.SUCCEEDED,
                SimulatedPayments::outcome);
    }

    @Override
    public void fail(UUID orderId) {
        change(orderId, Set.of(Status.REQUIRES_PAYMENT, Status.PROCESSING), Status.FAILED,
                SimulatedPayments::outcome);
    }

    @Override
    public void expire(UUID orderId) {
        change(orderId, Set.of(Status.REQUIRES_PAYMENT, Status.PROCESSING), Status.EXPIRED,
                SimulatedPayments::outcome);
    }

    private void change(UUID orderId, Set<Status> from, Status to, Function<SimulatedPayment, Object> event) {
        transactions.executeWithoutResult(status -> {
            SimulatedPayment payment = created(orderId);
            if (!from.contains(payment.status())) {
                throw new IllegalStateException("The " + payment.status() + " payment of order " + orderId
                        + " cannot become " + to);
            }
            SimulatedPayment changed = payments.setStatus(orderId, to, clock.instant());
            if (event != null) {
                publish(event.apply(changed), changed, Correlation.start(orderId.toString()));
            }
        });
    }

    private SimulatedPayment created(UUID orderId) {
        SimulatedPayment payment = payments.lock(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no payment"));
        if (payment.status() == Status.CREATION_REFUSED) {
            throw new IllegalStateException("The payment of order " + orderId + " was never created");
        }
        return payment;
    }

    /** The event that reports a payment's final status. */
    private static Object outcome(SimulatedPayment payment) {
        UUID orderId = payment.orderId();
        UUID paymentId = payment.paymentId();
        return switch (payment.status()) {
            case SUCCEEDED -> new PaymentSucceeded(orderId, paymentId, payment.amountPaise());
            case FAILED -> new PaymentFailed(orderId, paymentId);
            case EXPIRED -> new PaymentExpired(orderId, paymentId);
            case CANCELLED -> new PaymentCancelled(orderId, paymentId);
            case REQUIRES_PAYMENT, PROCESSING, CREATION_REFUSED -> throw new IllegalStateException(
                    "The payment of order " + orderId + " has no outcome yet: " + payment.status());
        };
    }

    private void reply(Object reply, SimulatedPayment payment, IncomingMessage<?> command) {
        publish(reply, payment, Correlation.causedBy(command));
    }

    private void publish(Object event, SimulatedPayment payment, Correlation correlation) {
        messages.publish(event, new Origin(AGGREGATE, payment.orderId(), payment.version()), correlation);
    }
}
