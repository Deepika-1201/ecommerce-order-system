package com.ecommerce.payments;

import java.util.UUID;

/**
 * Drives the simulated payments that stand in for the gateway until phase 7 (ADR-022, LLD §6.8). Each method runs
 * in a transaction of its own and publishes the event the real gateway's report would. A method the payment's status
 * does not allow throws {@link IllegalStateException}.
 */
public interface PaymentSimulator {

    /** The order's payment will not be created: Payments answers {@code CreatePayment} with a failure. */
    void refuseCreation(UUID orderId);

    /** The customer starts paying: until the attempt ends, a cancellation is refused. */
    void startAttempt(UUID orderId);

    /** The payment succeeds, also after it expired, within the gateway's grace: {@code PaymentSucceeded}. */
    void succeed(UUID orderId);

    /** The payment fails: {@code PaymentFailed}. */
    void fail(UUID orderId);

    /** The payment's time is up: {@code PaymentExpired}. */
    void expire(UUID orderId);
}
