package com.ecommerce.payments.simulator;

import java.time.Instant;
import java.util.UUID;

/** An order's simulated payment. A refused one has only its order id and status. */
record SimulatedPayment(UUID orderId, UUID paymentId, Long amountPaise, Status status, Instant expiresAt,
        long version) {

    enum Status {
        REQUIRES_PAYMENT,
        /** The customer is paying: an attempt is in flight. */
        PROCESSING,
        SUCCEEDED,
        FAILED,
        EXPIRED,
        CANCELLED,
        CREATION_REFUSED
    }
}
