package com.ecommerce.platform;

import java.time.Instant;
import java.util.UUID;

/** A delivered message: the envelope's fields (event model §2) and the payload read as {@code T}. */
public record IncomingMessage<T>(
        UUID messageId,
        String type,
        int version,
        Instant occurredAt,
        String source,
        String aggregateType,
        String aggregateId,
        long sequence,
        String correlationId,
        UUID causationId,
        String traceparent,
        T payload) {
}
