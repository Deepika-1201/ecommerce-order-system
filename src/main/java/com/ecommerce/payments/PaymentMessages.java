package com.ecommerce.payments;

import com.ecommerce.platform.MessageType;
import java.time.Instant;
import java.util.UUID;

/**
 * The order saga's commands to Payments, Payments' replies, and the payment events the saga consumes (LLD §6.2).
 * Replies and events come from aggregate {@code payment}, keyed by the order id, with the payment's version.
 */
public final class PaymentMessages {

    private PaymentMessages() {
    }

    /** Why an order is refunded; one refund per order and reason. */
    public enum RefundReason {
        ORDER_CANCELLED,
        STOCK_LOST_AFTER_PAYMENT,
        RETURNED_TO_ORIGIN,
        /** The gateway reported a success after the order had ended unpaid (FR-PAY5). */
        LATE_SUCCESS
    }

    /** One payment per order, payable until {@code expiresAt}: {@link PaymentCreated} or a failure. */
    @MessageType(name = "payments.create-payment")
    public record CreatePayment(UUID orderId, long amountPaise, Instant expiresAt) {
    }

    /** The customer pays at {@code checkoutUrl}. */
    @MessageType(name = "payments.payment-created")
    public record PaymentCreated(UUID orderId, UUID paymentId, String checkoutUrl) {
    }

    @MessageType(name = "payments.payment-creation-failed")
    public record PaymentCreationFailed(UUID orderId) {
    }

    /** Cancel before the customer pays: {@link PaymentCancelled}, {@link PaymentCancelRefused} or the outcome. */
    @MessageType(name = "payments.cancel-payment")
    public record CancelPayment(UUID orderId) {
    }

    @MessageType(name = "payments.payment-cancelled")
    public record PaymentCancelled(UUID orderId, UUID paymentId) {
    }

    /** An attempt is in flight, or the payment succeeded: its outcome follows. */
    @MessageType(name = "payments.payment-cancel-refused")
    public record PaymentCancelRefused(UUID orderId, UUID paymentId) {
    }

    /** Ask for the payment's outcome, at a deadline: the outcome, or {@link PaymentPending}. */
    @MessageType(name = "payments.check-payment")
    public record CheckPayment(UUID orderId) {
    }

    @MessageType(name = "payments.payment-pending")
    public record PaymentPending(UUID orderId, UUID paymentId) {
    }

    /** Refund a successful payment: {@link RefundInitiated}. */
    @MessageType(name = "payments.refund-payment")
    public record RefundPayment(UUID orderId, long amountPaise, RefundReason reason) {
    }

    @MessageType(name = "payments.refund-initiated")
    public record RefundInitiated(UUID orderId, UUID refundId, RefundReason reason, long amountPaise) {
    }

    @MessageType(name = "payments.payment-succeeded")
    public record PaymentSucceeded(UUID orderId, UUID paymentId, long amountPaise) {
    }

    @MessageType(name = "payments.payment-failed")
    public record PaymentFailed(UUID orderId, UUID paymentId) {
    }

    @MessageType(name = "payments.payment-expired")
    public record PaymentExpired(UUID orderId, UUID paymentId) {
    }
}
