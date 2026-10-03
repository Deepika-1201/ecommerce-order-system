package com.ecommerce.payments.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * The gateway's JSON, as its OpenAPI document defines it, in snake case through the application's mapper. Unknown
 * fields in its answers are ignored, as the contract asks. Shared by the HTTP adapter, the fake and the event parser.
 */
final class GatewayWire {

    private GatewayWire() {
    }

    record CustomerJson(String reference) {
    }

    record CreatePaymentJson(long amount, String currency, String merchantOrderId, String captureMethod,
                             CustomerJson customer, Long expiresInSeconds) {
    }

    record CreateCheckoutSessionJson(String paymentId, String returnUrl) {
    }

    record CancelPaymentJson(String reason) {
    }

    record CreateRefundJson(long amount, String reason, String merchantRefundId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentJson(String id, String object, String merchantOrderId, long amount, String currency, String status,
                       String captureMethod, CustomerJson customer, long amountCaptured, long amountRefunded,
                       int attemptCount, Instant expiresAt, Instant createdAt, Instant updatedAt, long version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundJson(String id, String object, String paymentId, String attemptId, long amount, String currency,
                      String status, String reason, String merchantRefundId, String initiatedBy, String provider,
                      Instant createdAt, Instant updatedAt, long version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckoutSessionJson(String id, String object, String paymentId, String url, String returnUrl,
                               Instant expiresAt, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventJson(String id, String type, Instant createdAt, Map<String, Object> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemJson(String type, String title, Integer status, String detail, String code) {
    }

    static GatewayPayment payment(PaymentJson json) {
        return new GatewayPayment(json.id(), json.merchantOrderId(), json.amount(),
                constant(GatewayPayment.Status.class, json.status()), json.version());
    }

    static GatewayRefund refund(RefundJson json) {
        return new GatewayRefund(json.id(), json.paymentId(), json.amount(),
                constant(GatewayRefund.Status.class, json.status()),
                constant(GatewayRefund.Initiator.class, json.initiatedBy()), json.merchantRefundId(), json.version());
    }

    /** The gateway's lower-case wire value of an enum constant. */
    static String wire(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("The gateway sent no " + type.getSimpleName());
        }
        return Enum.valueOf(type, wire.toUpperCase(Locale.ROOT));
    }
}
