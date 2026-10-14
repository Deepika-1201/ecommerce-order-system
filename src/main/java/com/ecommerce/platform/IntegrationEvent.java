package com.ecommerce.platform;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A schema-validated integration event; broker metadata has already been checked against its envelope. */
public record IntegrationEvent(UUID messageId, String type, String aggregateId, long sequence,
        Instant occurredAt, String correlationId, JsonNode data) {
}