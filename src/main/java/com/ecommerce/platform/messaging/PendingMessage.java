package com.ecommerce.platform.messaging;

import java.util.UUID;

/** An outbox row claimed for delivery. */
record PendingMessage(
        long id,
        UUID messageId,
        String destination,
        String aggregateId,
        String type,
        int version,
        String traceparent,
        String envelope,
        int attempts) {
}
