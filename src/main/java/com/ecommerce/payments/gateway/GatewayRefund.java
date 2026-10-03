package com.ecommerce.payments.gateway;

/** A refund as the gateway reports it (LLD §7.3). Amounts are in paise. */
public record GatewayRefund(String id, String paymentId, long amountPaise, Status status, Initiator initiatedBy,
                            String merchantRefundId, long version) {

    public enum Status {
        INITIATED,
        UNKNOWN,
        PENDING,
        SUCCEEDED,
        FAILED;

        public boolean isFinal() {
            return this == SUCCEEDED || this == FAILED;
        }
    }

    /** Who asked for the refund: this system, or the gateway returning a late or a second success. */
    public enum Initiator {
        MERCHANT,
        SYSTEM_LATE_SUCCESS,
        SYSTEM_DUPLICATE_SUCCESS
    }
}
