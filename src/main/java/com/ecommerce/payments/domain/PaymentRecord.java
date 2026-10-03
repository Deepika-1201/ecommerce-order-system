package com.ecommerce.payments.domain;

import com.ecommerce.payments.gateway.GatewayPayment;
import java.time.Instant;
import java.util.UUID;

/** This system's view of an order's payment (LLD §7.4). The gateway remains the truth for money. */
record PaymentRecord(
        UUID id,
        UUID orderId,
        UUID customerId,
        long amountPaise,
        Instant expiresAt,
        Creation creation,
        String gatewayPaymentId,
        GatewayPayment.Status status,
        Long gatewayVersion,
        String checkoutUrl,
        boolean cancelRequested,
        Instant createdAt,
        long version) {

    /** Whether the gateway has the payment and its checkout session yet. */
    enum Creation {
        CREATING,
        CREATED,
        FAILED
    }

    boolean isFinal() {
        return status != null && status.isFinal();
    }

    /** Whether the gateway's report is newer than what was applied; the gateway counts versions from 0. */
    boolean isOlderThan(long reportedVersion) {
        return gatewayVersion == null || reportedVersion > gatewayVersion;
    }
}
