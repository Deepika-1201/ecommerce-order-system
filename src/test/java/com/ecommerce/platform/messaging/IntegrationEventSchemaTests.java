package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.InvalidIntegrationEventException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class IntegrationEventSchemaTests {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final String ORDER = "01900000-0000-7000-8000-000000000001";

    private final IntegrationEvents events;

    IntegrationEventSchemaTests() throws IOException {
        events = new SchemaIntegrationEvents(JSON);
    }

    @ParameterizedTest
    @MethodSource("examples")
    void allCommittedVersionOnePayloadsRemainReadable(JsonNode example) {
        ObjectNode envelope = envelope(example);
        IntegrationEvent event = read(envelope);
        assertThat(event.type()).isEqualTo(example.path("type").asString());
        assertThat(event.data()).isEqualTo(example.path("data"));
    }

    @ParameterizedTest
    @MethodSource("examples")
    void addingOptionalFieldsIsBackwardCompatible(JsonNode example) {
        ObjectNode envelope = envelope(example);
        envelope.put("future_envelope_field", "ignored");
        ((ObjectNode) envelope.path("data")).put("future_data_field", true);

        assertThat(read(envelope).messageId()).isEqualTo(UUID.fromString(envelope.path("message_id").asString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"message_id", "type", "version", "occurred_at", "source", "aggregate_type",
            "aggregate_id", "sequence", "correlation_id", "data"})
    void missingEnvelopeFieldsArePermanentFailures(String field) throws IOException {
        ObjectNode envelope = placed();
        envelope.remove(field);
        assertInvalid(envelope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_id", "customer_id", "order_number", "amount_paise", "line_count"})
    void requiredPayloadFieldsCannotDisappear(String field) throws IOException {
        ObjectNode envelope = placed();
        ((ObjectNode) envelope.path("data")).remove(field);
        assertInvalid(envelope);
    }

    @Test
    void versionsSourcesDatesIdentifiersAndIntegerBoundsAreChecked() throws IOException {
        for (Map.Entry<String, JsonNode> invalid : Map.of(
                "version", JSON.valueToTree(2),
                "source", JSON.valueToTree("ecommerce/inventory"),
                "aggregate_type", JSON.valueToTree("stock"),
                "message_id", JSON.valueToTree("1-1-1-1-1"),
                "occurred_at", JSON.valueToTree("not-a-date"),
                "sequence", JSON.valueToTree(-1),
                "data", JSON.nullNode()).entrySet()) {
            ObjectNode envelope = placed();
            envelope.set(invalid.getKey(), invalid.getValue());
            assertInvalid(envelope);
        }
        ObjectNode envelope = placed();
        envelope.put("sequence", 1.5);
        assertInvalid(envelope);
        envelope.set("sequence", JSON.readTree("9223372036854775808"));
        assertInvalid(envelope);
        envelope = placed();
        ((ObjectNode) envelope.path("data")).put("amount_paise", -1);
        assertInvalid(envelope);
    }

    @Test
    void zeroAndIntegersBeyondDoublePrecisionKeepTheirExactValues() throws IOException {
        ObjectNode envelope = placed();
        envelope.put("sequence", 0);
        assertThat(read(envelope).sequence()).isZero();
        envelope.put("sequence", 9_007_199_254_740_993L);
        assertThat(read(envelope).sequence()).isEqualTo(9_007_199_254_740_993L);
    }

    @Test
    void topicKeyAndPayloadIdentityMustAgree() throws IOException {
        ObjectNode envelope = placed();
        byte[] value = JSON.writeValueAsBytes(envelope);
        assertThatThrownBy(() -> events.read(IntegrationEvents.STOCK_TOPIC, bytes(ORDER), value))
                .isInstanceOf(InvalidIntegrationEventException.class);
        assertThatThrownBy(() -> events.read(IntegrationEvents.ORDER_TOPIC, bytes("wrong"), value))
                .isInstanceOf(InvalidIntegrationEventException.class);
        envelope.put("aggregate_id", UUID.randomUUID().toString());
        assertInvalid(envelope);
    }

    @Test
    void nullMalformedOversizedAndInvalidUtf8RecordsArePermanentFailures() {
        for (byte[] value : List.of(bytes("{"), bytes("null"), bytes("{}"), new byte[256 * 1024 + 1],
                new byte[] {(byte) 0xc3, (byte) 0x28})) {
            assertThatThrownBy(() -> events.read(IntegrationEvents.ORDER_TOPIC, bytes(ORDER), value))
                    .isInstanceOf(InvalidIntegrationEventException.class);
        }
        assertThatThrownBy(() -> events.read(IntegrationEvents.ORDER_TOPIC, bytes(ORDER), null))
                .isInstanceOf(InvalidIntegrationEventException.class);
        assertThatThrownBy(() -> events.read(IntegrationEvents.ORDER_TOPIC, null, bytes("{}")))
                .isInstanceOf(InvalidIntegrationEventException.class);
    }

    private void assertInvalid(ObjectNode envelope) {
        assertThatThrownBy(() -> read(envelope)).isInstanceOf(InvalidIntegrationEventException.class);
    }

    private IntegrationEvent read(ObjectNode envelope) {
        boolean stock = envelope.path("type").asString().equals("stock.level_changed");
        return events.read(stock ? IntegrationEvents.STOCK_TOPIC : IntegrationEvents.ORDER_TOPIC,
                bytes(envelope.path("aggregate_id").asString()), JSON.writeValueAsBytes(envelope));
    }

    private static ObjectNode placed() throws IOException {
        return envelope(examples().findFirst().orElseThrow());
    }

    private static ObjectNode envelope(JsonNode example) {
        boolean stock = example.path("type").asString().equals("stock.level_changed");
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("message_id", UUID.randomUUID().toString());
        envelope.put("type", example.path("type").asString());
        envelope.put("version", 1);
        envelope.put("occurred_at", "2026-10-14T08:00:00Z");
        envelope.put("source", stock ? "ecommerce/inventory" : "ecommerce/ordering");
        envelope.put("aggregate_type", stock ? "stock" : "order");
        envelope.put("aggregate_id", stock ? "[\"SKU-ONE\",\"BLR1\"]" : ORDER);
        envelope.put("sequence", 1);
        envelope.put("correlation_id", ORDER);
        envelope.set("data", example.path("data").deepCopy());
        return envelope;
    }

    private static Stream<JsonNode> examples() throws IOException {
        return JSON.readTree(new ClassPathResource("contracts/integration-events-v1.json")
                .getContentAsString(StandardCharsets.UTF_8)).valueStream();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}