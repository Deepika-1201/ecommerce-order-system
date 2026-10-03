package com.ecommerce.payments;

import java.util.UUID;

/**
 * Drives the fake gateway that stands in for the Payment Gateway when none is configured (LLD §7.10), the way a
 * customer and the PSPs would. Each change the gateway reports by webhook reaches Payments through the same inbox
 * and tasks as a webhook. A method the payment's status does not allow throws {@link IllegalStateException}.
 */
public interface PaymentSimulator {

    /** The gateway will refuse to create the order's payment, as for an invalid request. */
    void refuseCreation(UUID orderId);

    /** The gateway will time out whenever the order's payment is created, until the creation budget is spent. */
    void timeOutCreation(UUID orderId);

    /** The gateway will create the order's payment, but time out whenever its checkout session is created. */
    void timeOutCheckout(UUID orderId);

    /** The customer starts paying: until the attempt ends, the payment cannot be cancelled. */
    void startAttempt(UUID orderId);

    /** The attempt fails: the payment can be paid, or cancelled, again ({@code payment.attempt_failed}). */
    void failAttempt(UUID orderId);

    /**
     * The payment succeeds ({@code payment.succeeded}). If it had already ended, the gateway keeps it as it was and
     * refunds the money (its {@code AUTO_REFUND} policy), unless {@link #acceptLateSuccess} was called for the order;
     * if it had already succeeded, it refunds the second charge. Either refund stays pending until
     * {@link #succeedRefund} or {@link #failRefund}.
     */
    void succeed(UUID orderId);

    /** A late success of this order's payment will be accepted: the payment becomes succeeded (the ACCEPT policy). */
    void acceptLateSuccess(UUID orderId);

    /** The payment fails for good ({@code payment.failed}). */
    void fail(UUID orderId);

    /** The payment's time is up ({@code payment.expired}). */
    void expire(UUID orderId);

    /** The order's oldest pending refund succeeds ({@code refund.succeeded}). */
    void succeedRefund(UUID orderId);

    /** The order's oldest pending refund fails ({@code refund.failed}). */
    void failRefund(UUID orderId);
}
