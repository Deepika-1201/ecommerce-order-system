package com.ecommerce.payments.gateway;

/** A payment as the gateway reports it (LLD §7.3). Amounts are in paise. */
public record GatewayPayment(String id, String merchantOrderId, long amountPaise, Status status, long version) {

    /** The gateway's payment statuses; capture is automatic, so {@code AUTHORIZED} does not occur here. */
    public enum Status {
        REQUIRES_PAYMENT_METHOD,
        REQUIRES_ACTION,
        PROCESSING,
        AUTHORIZED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        EXPIRED;

        public boolean isFinal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
        }

        /** No attempt is in flight, so the gateway accepts a cancel (S6). */
        public boolean canBeCancelled() {
            return this == REQUIRES_PAYMENT_METHOD || this == REQUIRES_ACTION;
        }
    }
}
