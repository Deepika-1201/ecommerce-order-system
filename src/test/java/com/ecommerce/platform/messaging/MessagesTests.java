package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.platform.messaging.MessagingFixtures.SomethingPublic;
import com.ecommerce.platform.messaging.MessagingFixtures.StepRecorded;
import com.ecommerce.platform.messaging.MessagingFixtures.Unrouted;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(MessagingFixtures.class)
@TestPropertySource(properties = {
    "ecom.messaging.max-attempts=3",
    "spring.jackson.deserialization.fail-on-unknown-properties=true"
})
class MessagesTests extends IntegrationTest {

    @Autowired
    private Messages messages;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @BeforeEach
    void emptyOutbox() {
        PlatformTables.clear(jdbc);
    }

    @Test
    void publishingNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> messages.publish(new StepRecorded(1, "x"), origin("a", 1), Correlation.start("a")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionLeavesNoMessage() {
        transactions.executeWithoutResult(status -> {
            messages.publish(new StepRecorded(1, "x"), origin("a", 1), Correlation.start("a"));
            status.setRollbackOnly();
        });

        assertThat(rows()).isEmpty();
    }

    @Test
    void aCommittedMessageGetsOneRowPerSubscribedHandlerAndTopic() {
        transactions.executeWithoutResult(status -> {
            messages.publish(new StepRecorded(1, "x"), origin("a", 1), Correlation.start("a"));
            messages.publish(new SomethingPublic("hello"), origin("b", 4), Correlation.start("b"));
        });

        assertThat(rows())
                .extracting(OutboxRow::destination, OutboxRow::aggregateId, OutboxRow::sequence)
                .containsExactlyInAnyOrder(
                        tuple("handler:" + MessagingFixtures.RECORDER, "a", 1L),
                        tuple("handler:" + MessagingFixtures.AUDITOR, "a", 1L),
                        tuple("kafka:" + MessagingFixtures.TOPIC, "b", 4L));
        assertThat(rows().stream().filter(row -> row.aggregateId().equals("a")).map(OutboxRow::messageId).distinct())
                .as("one message, fanned out")
                .hasSize(1);
    }

    @Test
    void theEnvelopeCarriesTheEventModelFields() {
        // The clock ticks in microseconds, so compare at that precision.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID cause = UUID.randomUUID();
        transactions.executeWithoutResult(status -> messages.publish(new StepRecorded(7, "seventh"),
                origin("order-1", 7), new Correlation("order-1", cause)));

        OutboxRow row = rows().getFirst();
        JsonNode envelope = json.readTree(row.envelope());
        assertThat(envelope.get("message_id").asString()).isEqualTo(row.messageId().toString());
        assertThat(UUID.fromString(envelope.get("message_id").asString()).version()).isEqualTo(7);
        assertThat(envelope.get("type").asString()).isEqualTo("test.step-recorded");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(envelope.get("occurred_at").asString())).isBetween(before, Instant.now());
        assertThat(envelope.get("source").asString()).isEqualTo("ecommerce/platform");
        assertThat(envelope.get("aggregate_type").asString()).isEqualTo("test-aggregate");
        assertThat(envelope.get("aggregate_id").asString()).isEqualTo("order-1");
        assertThat(envelope.get("sequence").asLong()).isEqualTo(7);
        assertThat(envelope.get("correlation_id").asString()).isEqualTo("order-1");
        assertThat(envelope.get("causation_id").asString()).isEqualTo(cause.toString());
        assertThat(envelope.get("data").get("step").asInt()).isEqualTo(7);
        assertThat(envelope.get("data").get("note").asString()).isEqualTo("seventh");
    }

    @Test
    void aTypeNothingReceivesCannotBePublished() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                        messages.publish(new Unrouted("lost"), origin("a", 1), Correlation.start("a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.unrouted");
        assertThat(rows()).isEmpty();
    }

    private static Origin origin(String aggregateId, long sequence) {
        return new Origin("test-aggregate", aggregateId, sequence);
    }

    private List<OutboxRow> rows() {
        return jdbc.sql("SELECT message_id, destination, aggregate_id, sequence, envelope FROM platform.outbox")
                .query(OutboxRow.class)
                .list();
    }

    record OutboxRow(UUID messageId, String destination, String aggregateId, long sequence, String envelope) {
    }
}
