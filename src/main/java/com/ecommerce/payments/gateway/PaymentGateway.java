package com.ecommerce.payments.gateway;

import java.time.Duration;
import java.util.UUID;

/**
 * The Payment Gateway's merchant API, as Payments uses it (ADR-002, LLD §7.3). Every call may throw
 * {@link GatewayRefusedException}, an answer the gateway keeps for the key, or {@link GatewayUnavailableException},
 * after which the same call with the same key is safe.
 */
public interface PaymentGateway {

    GatewayPayment createPayment(NewPayment payment, String idempotencyKey);

    CheckoutSession createCheckoutSession(String paymentId, String returnUrl, String idempotencyKey);

    GatewayPayment cancelPayment(String paymentId, String idempotencyKey);

    GatewayPayment payment(String paymentId);

    GatewayRefund createRefund(String paymentId, NewRefund refund, String idempotencyKey);

    /** A payment for an order, payable for {@code expiresIn}. */
    record NewPayment(UUID orderId, UUID customerId, long amountPaise, Duration expiresIn) {
    }

    /** {@code merchantRefundId} identifies the refund in this system, so it can be found without its key. */
    record NewRefund(long amountPaise, String reason, String merchantRefundId) {
    }

    /** The hosted page where the customer pays. */
    record CheckoutSession(String id, String url) {
    }
}
