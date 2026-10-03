package com.ecommerce.payments.domain;

import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.gateway.GatewayRefund;
import java.util.UUID;

/**
 * A refund of an order's payment (LLD §7.4): requested by the saga, one per reason, or made by the gateway itself.
 * The gateway's own duplicate-success refunds have no reason.
 */
record RefundRecord(
        UUID id,
        UUID orderId,
        RefundReason reason,
        long amountPaise,
        GatewayRefund.Initiator initiatedBy,
        String gatewayRefundId,
        Status status,
        Long gatewayVersion) {

    /** Whether the gateway's report is newer than what was applied; the gateway counts versions from 0. */
    boolean isOlderThan(long reportedVersion) {
        return gatewayVersion == null || reportedVersion > gatewayVersion;
    }

    enum Status {
        REQUESTED,
        PENDING,
        SUCCEEDED,
        FAILED;

        static Status of(GatewayRefund.Status status) {
            return switch (status) {
                case INITIATED, UNKNOWN, PENDING -> PENDING;
                case SUCCEEDED -> SUCCEEDED;
                case FAILED -> FAILED;
            };
        }

        boolean isFinal() {
            return this == SUCCEEDED || this == FAILED;
        }
    }
}
