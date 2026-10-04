package com.ecommerce.ordering.domain;

import com.ecommerce.fulfillment.ShipmentMessages;
import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.inventory.StockMessages.CommitReservation;
import com.ecommerce.inventory.StockMessages.FulfillReservation;
import com.ecommerce.inventory.StockMessages.ReleaseReservation;
import com.ecommerce.inventory.StockMessages.ReservationCommitted;
import com.ecommerce.inventory.StockMessages.ReservationLost;
import com.ecommerce.inventory.StockMessages.ReserveStock;
import com.ecommerce.inventory.StockMessages.RestockReturn;
import com.ecommerce.inventory.StockMessages.StockReservationFailed;
import com.ecommerce.inventory.StockMessages.StockReserved;
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
import com.ecommerce.payments.PaymentMessages.RefundFailed;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.PaymentMessages.RefundSucceeded;
import com.ecommerce.pricing.CouponMessages.CommitCoupon;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.CouponUnavailable;
import com.ecommerce.pricing.CouponMessages.ReleaseCoupon;
import com.ecommerce.pricing.CouponMessages.ReserveCoupon;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The process of one order (LLD §6.5): the saga's state, with what its decisions need from the order. It decides
 * without I/O: a message, a cancellation or a deadline may change the step, the order's status and the deadline, and
 * leaves the commands to send in {@link #commands()}. The caller saves the change and publishes the commands in one
 * transaction, under the order's row lock. A message the step does not expect changes nothing: it is a duplicate, or
 * late, or one that a duplicate overtook.
 */
final class OrderProcess {

    static final Duration STEP_TIMEOUT = Duration.ofMinutes(2);
    static final Duration CANCEL_TIMEOUT = Duration.ofMinutes(10);
    static final Duration RECHECK_INTERVAL = Duration.ofMinutes(5);
    static final Duration HANDOVER_TIMEOUT = Duration.ofHours(24);
    static final Duration DELIVERY_TIMEOUT = Duration.ofDays(14);
    static final Duration RETURN_TIMEOUT = Duration.ofDays(21);
    /** From this attempt at a step, a deadline that passes raises an alert (LLD §6.7). */
    static final int ALERT_FROM_ATTEMPT = 3;

    /** What a cancellation request did (LLD §6.6). */
    enum CancelOutcome {
        REQUESTED,
        /** The order was already cancelling or cancelled: nothing changed. */
        ALREADY_CANCELLING,
        /** Shipped, delivered, returned or rejected orders cannot be cancelled. */
        REFUSED
    }

    private final OrderFacts order;
    private final OrderingProperties settings;
    private final long version;
    private OrderStatus status;
    private OrderReason reason;
    private String shortSku;
    private UUID paymentId;
    private String checkoutUrl;
    private Long refundAmountPaise;
    private RefundStatus refundStatus;
    private RefundReason refundReason;
    private Step step;
    private Cancellation cancellation;
    private boolean paymentSucceeded;
    private Instant holdExpiresAt;
    private Instant deadlineAt;
    private int attempts;

    private final List<Object> commands = new ArrayList<>();
    private boolean changed;
    private boolean overdue;
    private boolean refundFailed;

    OrderProcess(OrderFacts order, ProcessState state, OrderingProperties settings) {
        this.order = order;
        this.settings = settings;
        this.version = state.version();
        this.status = state.status();
        this.reason = state.reason();
        this.shortSku = state.shortSku();
        this.paymentId = state.paymentId();
        this.checkoutUrl = state.checkoutUrl();
        this.refundAmountPaise = state.refundAmountPaise();
        this.refundStatus = state.refundStatus();
        this.refundReason = state.refundReason();
        this.step = state.step();
        this.cancellation = state.cancellation();
        this.paymentSucceeded = state.paymentSucceeded();
        this.holdExpiresAt = state.holdExpiresAt();
        this.deadlineAt = state.deadlineAt();
        this.attempts = state.attempts();
    }

    /** A new order's process: it starts by reserving the stock (LLD §6.3). */
    static OrderProcess start(OrderFacts order, OrderingProperties settings, Instant now) {
        OrderProcess process = new OrderProcess(order, ProcessState.placed(), settings);
        process.enter(Step.RESERVING_STOCK, now);
        return process;
    }

    /** Applies a participant's reply or event, as the step's row of LLD §6.5 says, and §7.9 for refunds' ends. */
    void decide(Object message, Instant now) {
        switch (message) {
            case RefundSucceeded ended -> refundEnded(ended.reason(), ended.amountPaise(), true, now);
            case RefundFailed ended -> refundEnded(ended.reason(), ended.amountPaise(), false, now);
            default -> decideInStep(message, now);
        }
    }

    private void decideInStep(Object message, Instant now) {
        switch (step) {
            case RESERVING_STOCK -> reservingStock(message, now);
            case RESERVING_COUPON -> reservingCoupon(message, now);
            case CREATING_PAYMENT -> creatingPayment(message, now);
            case AWAITING_PAYMENT -> awaitingPayment(message, now);
            case COMMITTING_STOCK -> committingStock(message, now);
            case AWAITING_HANDOVER -> awaitingHandover(message, now);
            case AWAITING_DELIVERY -> awaitingDelivery(message, now);
            case AWAITING_RETURN -> awaitingReturn(message, now);
            case CANCELLING_PAYMENT, AWAITING_PAYMENT_OUTCOME -> cancellingPayment(message, now);
            case CANCELLING_SHIPMENT -> cancellingShipment(message, now);
            case REFUNDING -> refunding(message, now);
            case DONE -> done(message, now);
        }
    }

    /**
     * A cancellation by the customer or support (LLD §6.6). Before the payment's outcome, the current step goes on and
     * its reply releases what is held; a payment waiting to be paid, or a booked shipment, is cancelled now.
     */
    CancelOutcome requestCancellation(Cancellation request, Instant now) {
        switch (status) {
            case CANCELLING, CANCELLED -> {
                return CancelOutcome.ALREADY_CANCELLING;
            }
            case SHIPPED, DELIVERED, DELIVERY_FAILED, RETURNED_TO_ORIGIN, REJECTED -> {
                return CancelOutcome.REFUSED;
            }
            case PLACED -> cancelling(request);
            case AWAITING_PAYMENT -> {
                cancelling(request);
                if (step == Step.AWAITING_PAYMENT) {
                    enter(Step.CANCELLING_PAYMENT, now);
                }
            }
            case CONFIRMED -> {
                cancelling(request);
                enter(Step.CANCELLING_SHIPMENT, now);
            }
        }
        return CancelOutcome.REQUESTED;
    }

    /**
     * The step's deadline passed (LLD §6.7): send the step's command again, or alert and wait longer. It only ever
     * asks; it changes nothing if the deadline has not passed, as when another sweep acted first.
     */
    void onDeadline(Instant now) {
        if (deadlineAt == null || deadlineAt.isAfter(now)) {
            return;
        }
        attempts++;
        Object command = stepCommand();
        if (command == null) {
            overdue = true;
        } else {
            send(command);
            overdue = attempts >= ALERT_FROM_ATTEMPT;
        }
        deadlineAt = now.plus(timeout(step));
        changed = true;
    }

    /**
     * The customer is back from the hosted checkout (LLD §7.8): while the order waits for its payment, ask for the
     * outcome now rather than at the deadline. Returns whether it asked.
     */
    boolean requestPaymentCheck() {
        if (step != Step.AWAITING_PAYMENT && step != Step.AWAITING_PAYMENT_OUTCOME) {
            return false;
        }
        send(new CheckPayment(orderId()));
        return true;
    }

    private void reservingStock(Object message, Instant now) {
        switch (message) {
            case StockReserved reserved -> {
                holdExpiresAt = reserved.expiresAt();
                if (cancelling()) {
                    send(new ReleaseReservation(orderId()));
                    finish(OrderStatus.CANCELLED, cancellation.reason(), now);
                } else {
                    enter(order.couponId() == null ? Step.CREATING_PAYMENT : Step.RESERVING_COUPON, now);
                }
            }
            case StockReservationFailed failed -> {
                if (cancelling()) {
                    finish(OrderStatus.CANCELLED, cancellation.reason(), now);
                } else {
                    shortSku = failed.sku();
                    finish(OrderStatus.REJECTED, OrderReason.OUT_OF_STOCK, now);
                }
            }
            default -> {
                // Not this step's reply.
            }
        }
    }

    private void reservingCoupon(Object message, Instant now) {
        switch (message) {
            case CouponReserved _ -> {
                if (cancelling()) {
                    release();
                    finish(OrderStatus.CANCELLED, cancellation.reason(), now);
                } else {
                    enter(Step.CREATING_PAYMENT, now);
                }
            }
            case CouponUnavailable _ -> {
                send(new ReleaseReservation(orderId()));
                if (cancelling()) {
                    finish(OrderStatus.CANCELLED, cancellation.reason(), now);
                } else {
                    finish(OrderStatus.REJECTED, OrderReason.COUPON_UNAVAILABLE, now);
                }
            }
            default -> {
                // Not this step's reply.
            }
        }
    }

    private void creatingPayment(Object message, Instant now) {
        switch (message) {
            case PaymentCreated created -> {
                paymentId = created.paymentId();
                if (cancelling()) {
                    enter(Step.CANCELLING_PAYMENT, now);
                } else {
                    checkoutUrl = created.checkoutUrl();
                    moveTo(OrderStatus.AWAITING_PAYMENT);
                    enter(Step.AWAITING_PAYMENT, now);
                }
            }
            case PaymentCreationFailed _ -> {
                release();
                if (cancelling()) {
                    finish(OrderStatus.CANCELLED, cancellation.reason(), now);
                } else {
                    finish(OrderStatus.REJECTED, OrderReason.PAYMENTS_UNAVAILABLE, now);
                }
            }
            case PaymentSucceeded outcome -> outcomeBeforeCreated(outcome.paymentId(), outcome, now);
            case PaymentFailed outcome -> outcomeBeforeCreated(outcome.paymentId(), outcome, now);
            case PaymentExpired outcome -> outcomeBeforeCreated(outcome.paymentId(), outcome, now);
            case PaymentCancelled outcome -> outcomeBeforeCreated(outcome.paymentId(), outcome, now);
            default -> {
                // Not this step's reply.
            }
        }
    }

    /** The payment's outcome overtook {@code PaymentCreated}: the payment exists, so carry on as if it had come. */
    private void outcomeBeforeCreated(UUID createdPaymentId, Object outcome, Instant now) {
        paymentId = createdPaymentId;
        if (cancelling()) {
            cancellingPayment(outcome, now);
        } else {
            moveTo(OrderStatus.AWAITING_PAYMENT);
            awaitingPayment(outcome, now);
        }
    }

    private void awaitingPayment(Object message, Instant now) {
        switch (message) {
            case PaymentSucceeded _ -> {
                paymentSucceeded = true;
                enter(Step.COMMITTING_STOCK, now);
            }
            case PaymentFailed _, PaymentCancelled _ -> {
                release();
                finish(OrderStatus.CANCELLED, OrderReason.PAYMENT_FAILED, now);
            }
            case PaymentExpired _ -> {
                release();
                finish(OrderStatus.CANCELLED, OrderReason.PAYMENT_EXPIRED, now);
            }
            case PaymentPending _ -> checkAgainLater(now);
            default -> {
                // Not an outcome.
            }
        }
    }

    private void committingStock(Object message, Instant now) {
        switch (message) {
            case ReservationCommitted _ -> {
                if (cancelling()) {
                    release();
                    finishWithRefund(OrderStatus.CANCELLED, cancellation.reason(), RefundReason.ORDER_CANCELLED, now);
                } else {
                    if (order.couponId() != null) {
                        send(new CommitCoupon(orderId()));
                    }
                    send(new CreateShipment(orderId(), order.deliveryAddressId(), order.lines().stream()
                            .map(line -> new ShipmentMessages.Line(line.sku(), line.quantity()))
                            .toList()));
                    moveTo(OrderStatus.CONFIRMED);
                    enter(Step.AWAITING_HANDOVER, now);
                }
            }
            case ReservationLost _ -> {
                releaseCoupon();
                if (cancelling()) {
                    finishWithRefund(OrderStatus.CANCELLED, cancellation.reason(), RefundReason.ORDER_CANCELLED, now);
                } else {
                    finishWithRefund(OrderStatus.CANCELLED, OrderReason.STOCK_LOST_AFTER_PAYMENT,
                            RefundReason.STOCK_LOST_AFTER_PAYMENT, now);
                }
            }
            default -> {
                // Not this step's reply.
            }
        }
    }

    private void awaitingHandover(Object message, Instant now) {
        switch (message) {
            case ShipmentHandedOver _, ShipmentDelivered _, ShipmentReturnInitiated _, ShipmentReturnedToOrigin _ -> {
                send(new FulfillReservation(orderId()));
                moveTo(OrderStatus.SHIPPED);
                enter(Step.AWAITING_DELIVERY, now);
                awaitingDelivery(message, now);
            }
            default -> {
                // Not a shipment event.
            }
        }
    }

    private void awaitingDelivery(Object message, Instant now) {
        switch (message) {
            case ShipmentDelivered _ -> {
                moveTo(OrderStatus.DELIVERED);
                enter(Step.DONE, now);
            }
            case ShipmentReturnInitiated _, ShipmentReturnedToOrigin _ -> {
                moveTo(OrderStatus.DELIVERY_FAILED);
                enter(Step.AWAITING_RETURN, now);
                awaitingReturn(message, now);
            }
            default -> {
                // Not a later shipment event.
            }
        }
    }

    private void awaitingReturn(Object message, Instant now) {
        if (message instanceof ShipmentReturnedToOrigin) {
            send(new RestockReturn(orderId()));
            finishWithRefund(OrderStatus.RETURNED_TO_ORIGIN, null, RefundReason.RETURNED_TO_ORIGIN, now);
        }
    }

    /** {@code CANCELLING_PAYMENT}, and {@code AWAITING_PAYMENT_OUTCOME} after the cancellation was refused. */
    private void cancellingPayment(Object message, Instant now) {
        switch (message) {
            case PaymentCancelled _, PaymentFailed _, PaymentExpired _ -> {
                release();
                finish(OrderStatus.CANCELLED, cancellation.reason(), now);
            }
            case PaymentSucceeded _ -> {
                paymentSucceeded = true;
                release();
                finishWithRefund(OrderStatus.CANCELLED, cancellation.reason(), RefundReason.ORDER_CANCELLED, now);
            }
            case PaymentCancelRefused _ when step == Step.CANCELLING_PAYMENT ->
                    enter(Step.AWAITING_PAYMENT_OUTCOME, now);
            case PaymentPending _ when step == Step.AWAITING_PAYMENT_OUTCOME -> checkAgainLater(now);
            default -> {
                // Not an answer to the cancellation, nor an outcome.
            }
        }
    }

    private void cancellingShipment(Object message, Instant now) {
        switch (message) {
            case ShipmentCancelled _ -> {
                release();
                finishWithRefund(OrderStatus.CANCELLED, cancellation.reason(), RefundReason.ORDER_CANCELLED, now);
            }
            case ShipmentCancelRefused _ -> {
                send(new FulfillReservation(orderId()));
                moveTo(OrderStatus.SHIPPED);
                enter(Step.AWAITING_DELIVERY, now);
            }
            default -> awaitingHandover(message, now);
        }
    }

    private void refunding(Object message, Instant now) {
        if (message instanceof RefundInitiated initiated && initiated.reason() == refundReason) {
            refundStatus = RefundStatus.INITIATED;
            enter(Step.DONE, now);
        }
    }

    /**
     * A refund ended (LLD §7.9). The order's own refund ends, and with it a {@code REFUNDING} step; once ended, it stays
     * so. An order without a refund records the gateway's late-success refund (S13, FR-PAY5) whatever its step. A
     * failed refund raises an alert (S18).
     */
    private void refundEnded(RefundReason why, long amountPaise, boolean succeeded, Instant now) {
        if (refundReason == null && why == RefundReason.LATE_SUCCESS) {
            refundReason = why;
            refundAmountPaise = amountPaise;
        } else if (why != refundReason || refundStatus == RefundStatus.SUCCEEDED
                || refundStatus == RefundStatus.FAILED) {
            return;
        }
        refundStatus = succeeded ? RefundStatus.SUCCEEDED : RefundStatus.FAILED;
        refundFailed = !succeeded;
        changed = true;
        if (step == Step.REFUNDING) {
            enter(Step.DONE, now);
        }
    }

    /** Undoes what a late reply did elsewhere (LLD §6.5): money taken from an unpaid order, a use held for nothing. */
    private void done(Object message, Instant now) {
        switch (message) {
            case PaymentSucceeded _ when !paymentSucceeded -> {
                paymentSucceeded = true;
                requestRefund(RefundReason.LATE_SUCCESS, now);
            }
            case CouponReserved _ when status == OrderStatus.REJECTED || status == OrderStatus.CANCELLED ->
                    send(new ReleaseCoupon(orderId()));
            default -> {
                // The process is over.
            }
        }
    }

    private void cancelling(Cancellation request) {
        moveTo(OrderStatus.CANCELLING);
        cancellation = request;
    }

    private boolean cancelling() {
        return status == OrderStatus.CANCELLING;
    }

    private void release() {
        send(new ReleaseReservation(orderId()));
        releaseCoupon();
    }

    private void releaseCoupon() {
        if (order.couponId() != null) {
            send(new ReleaseCoupon(orderId()));
        }
    }

    private void finish(OrderStatus finalStatus, OrderReason finalReason, Instant now) {
        moveTo(finalStatus);
        reason = finalReason;
        enter(Step.DONE, now);
    }

    /** The order takes its final status in the transaction that requests its refund (LLD §6.4). */
    private void finishWithRefund(OrderStatus finalStatus, OrderReason finalReason, RefundReason why, Instant now) {
        moveTo(finalStatus);
        reason = finalReason;
        requestRefund(why, now);
    }

    /** Always the grand total (FR-PAY4). */
    private void requestRefund(RefundReason why, Instant now) {
        refundReason = why;
        refundAmountPaise = order.grandTotalPaise();
        refundStatus = RefundStatus.REQUESTED;
        enter(Step.REFUNDING, now);
    }

    /**
     * A pending payment is asked about again in 5 minutes, unless the deadline already comes later: before the hold's
     * expiry, as after the customer's return poll (LLD §7.8), the payment is simply awaited.
     */
    private void checkAgainLater(Instant now) {
        Instant next = now.plus(RECHECK_INTERVAL);
        if (deadlineAt == null || next.isAfter(deadlineAt)) {
            deadlineAt = next;
            changed = true;
        }
    }

    /** Moves to the step, with its deadline; a step that waits for a command's reply sends the command. */
    private void enter(Step next, Instant now) {
        step = next;
        attempts = 0;
        changed = true;
        if (next != Step.AWAITING_PAYMENT) {
            checkoutUrl = null;
        }
        deadlineAt = switch (next) {
            case AWAITING_PAYMENT, AWAITING_PAYMENT_OUTCOME -> holdExpiresAt;
            case DONE -> null;
            default -> now.plus(timeout(next));
        };
        switch (next) {
            case RESERVING_STOCK, RESERVING_COUPON, CREATING_PAYMENT, COMMITTING_STOCK, CANCELLING_PAYMENT,
                    CANCELLING_SHIPMENT, REFUNDING -> {
                send(stepCommand());
                attempts = 1;
            }
            default -> {
                // Waits for an event, not a reply.
            }
        }
    }

    /** The command the step waits on, sent on entry and again at each deadline; {@code null} if it only waits. */
    private Object stepCommand() {
        UUID orderId = orderId();
        return switch (step) {
            case RESERVING_STOCK -> new ReserveStock(orderId, order.lines(), settings.hold());
            case RESERVING_COUPON -> new ReserveCoupon(orderId, order.couponId(), order.customerId());
            case CREATING_PAYMENT -> new CreatePayment(orderId, order.customerId(), order.grandTotalPaise(),
                    settings.paymentExpiry(holdExpiresAt));
            case AWAITING_PAYMENT, AWAITING_PAYMENT_OUTCOME -> new CheckPayment(orderId);
            case COMMITTING_STOCK -> new CommitReservation(orderId);
            case CANCELLING_PAYMENT -> new CancelPayment(orderId);
            case CANCELLING_SHIPMENT -> new CancelShipment(orderId);
            case REFUNDING -> new RefundPayment(orderId, refundAmountPaise, refundReason);
            case AWAITING_HANDOVER, AWAITING_DELIVERY, AWAITING_RETURN, DONE -> null;
        };
    }

    /** How long the step waits before its deadline passes again (LLD §6.7). */
    private static Duration timeout(Step step) {
        return switch (step) {
            case RESERVING_STOCK, RESERVING_COUPON, CREATING_PAYMENT, COMMITTING_STOCK -> STEP_TIMEOUT;
            case AWAITING_PAYMENT, AWAITING_PAYMENT_OUTCOME -> RECHECK_INTERVAL;
            case CANCELLING_PAYMENT, CANCELLING_SHIPMENT, REFUNDING -> CANCEL_TIMEOUT;
            case AWAITING_HANDOVER -> HANDOVER_TIMEOUT;
            case AWAITING_DELIVERY -> DELIVERY_TIMEOUT;
            case AWAITING_RETURN -> RETURN_TIMEOUT;
            case DONE -> throw new IllegalStateException("A finished process has no deadline");
        };
    }

    private void moveTo(OrderStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Order " + orderId() + " cannot move from " + status + " to " + next);
        }
        status = next;
        changed = true;
    }

    private void send(Object command) {
        commands.add(command);
        changed = true;
    }

    UUID orderId() {
        return order.orderId();
    }

    UUID customerId() {
        return order.customerId();
    }

    OrderStatus status() {
        return status;
    }

    Step step() {
        return step;
    }

    /** Whether the last input changed anything; if not, there is nothing to save or send. */
    boolean changed() {
        return changed;
    }

    /** Whether the deadline that just passed should raise an alert (LLD §6.7). */
    boolean overdue() {
        return overdue;
    }

    /** Whether the last input reported a failed refund, which needs a person (S18). */
    boolean refundFailed() {
        return refundFailed;
    }

    RefundReason refundReason() {
        return refundReason;
    }

    Long refundAmountPaise() {
        return refundAmountPaise;
    }

    /** The commands to publish, in order, with the change. */
    List<Object> commands() {
        return List.copyOf(commands);
    }

    /** The version the change is saved with, which orders the commands it sends (LLD §2.3). */
    long sequence() {
        return version + 1;
    }

    /** The state to save; its version is still the one loaded. */
    ProcessState state() {
        return new ProcessState(status, reason, shortSku, paymentId, checkoutUrl, refundAmountPaise, refundStatus,
                refundReason, step, cancellation, paymentSucceeded, holdExpiresAt, deadlineAt, attempts, version);
    }
}
