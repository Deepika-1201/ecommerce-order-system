package com.ecommerce.platform.messaging;

import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.InvalidIntegrationEventException;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class SchemaIntegrationEvents implements IntegrationEvents {

    private static final int MAX_BYTES = 256 * 1024;
    private static final String CONTRACT = "https://ecommerce.invalid/events/integration-event-v1.json";

    private final Schema schema;
    private final JsonMapper json;

    SchemaIntegrationEvents(JsonMapper json) throws IOException {
        this.json = json;
        String document = new ClassPathResource("events/integration-event-v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        this.schema = SchemaRegistry.withDialect(Dialects.getDraft202012(),
                builder -> builder.schemas(Map.of(CONTRACT, document))).getSchema(SchemaLocation.of(CONTRACT));
    }

    @Override
    public IntegrationEvent read(String topic, byte[] key, byte[] value) {
        if (key == null || value == null || value.length > MAX_BYTES || key.length > 1024) {
            throw new InvalidIntegrationEventException();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
            JsonNode node = json.readTree(text);
            if (node == null || !schema.validate(node, (Consumer<ExecutionContext>) context ->
                    context.executionConfig(config -> config.formatAssertionsEnabled(true))).isEmpty()) {
                throw new InvalidIntegrationEventException();
            }
            Envelope envelope = json.treeToValue(node, Envelope.class);
            JsonNode data = envelope.data();
            boolean stock = envelope.type().equals("stock.level_changed");
            String expectedTopic = stock ? STOCK_TOPIC : ORDER_TOPIC;
            String expectedKey = stock
                    ? json.writeValueAsString(List.of(data.path("sku").asString(), data.path("location_code").asString()))
                    : data.path("order_id").asString();
            if (!expectedTopic.equals(topic) || !expectedKey.equals(envelope.aggregateId())
                    || !Arrays.equals(key, expectedKey.getBytes(StandardCharsets.UTF_8))) {
                throw new InvalidIntegrationEventException();
            }
            return new IntegrationEvent(envelope.messageId(), envelope.type(), envelope.aggregateId(),
                    envelope.sequence(), envelope.occurredAt(), envelope.correlationId(), envelope.data());
        } catch (CharacterCodingException | JacksonException | IllegalArgumentException failure) {
            throw new InvalidIntegrationEventException();
        }
    }
}