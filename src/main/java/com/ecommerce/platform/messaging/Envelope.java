package com.ecommerce.platform.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The serialized form of every message (event model §2); stored as text in the outbox and sent as-is to Kafka. */
record Envelope(
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
        JsonNode data) {
}
