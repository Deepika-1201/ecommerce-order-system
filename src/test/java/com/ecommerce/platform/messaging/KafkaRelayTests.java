package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.messaging.MessagingFixtures.Publisher;
import com.ecommerce.platform.messaging.MessagingFixtures.SomethingPublic;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The Kafka relay against an embedded broker (LLD §2.5). */
@Import(MessagingFixtures.class)
@EmbeddedKafka(partitions = 1, topics = MessagingFixtures.TOPIC)
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@DirtiesContext
class KafkaRelayTests extends IntegrationTest {

    @Autowired
    private KafkaRelay relay;

    @Autowired
    private Publisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private Consumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        PlatformTables.clear(jdbc);
        Map<String, Object> properties = KafkaTestUtils.consumerProps(broker, "relay-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, MessagingFixtures.TOPIC);
    }

    @AfterEach
    void unsubscribe() {
        consumer.close();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_delivery ON platform.outbox").update();
    }

    @Test
    void sendsTheEnvelopeKeyedByAggregateWithTypeAndVersionHeaders() {
        String order = "order-" + UUID.randomUUID();
        publisher.publish(new SomethingPublic("hello"), order, 1);

        assertThat(relay.relayBatch()).isEqualTo(1);

        ConsumerRecord<String, String> record = receive(Set.of(order), 1).getFirst();
        assertThat(record.key()).isEqualTo(order);
        assertThat(json.readTree(record.value()).get("type").asString()).isEqualTo("test.something-public");
        assertThat(json.readTree(record.value()).get("data").get("note").asString()).isEqualTo("hello");
        assertThat(header(record, "type")).isEqualTo("test.something-public");
        assertThat(header(record, "version")).isEqualTo("1");
        assertThat(pending()).isZero();
    }

    @Test
    void aCrashBetweenSendingAndCommittingSendsTheSameMessageAgain() {
        String order = "order-" + UUID.randomUUID();
        publisher.publish(new SomethingPublic("twice"), order, 1);
        failMarkingDelivered(order);

        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(pending()).as("the send happened but the commit did not").isEqualTo(1);

        jdbc.sql("DROP TRIGGER test_fail_delivery ON platform.outbox").update();
        jdbc.sql("UPDATE platform.outbox SET next_attempt_at = now()").update();
        assertThat(relay.relayBatch()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = receive(Set.of(order), 2);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(record -> json.readTree(record.value()).get("message_id").asString())
                .as("consumers deduplicate on message_id")
                .containsOnly(json.readTree(records.getFirst().value()).get("message_id").asString());
        assertThat(pending()).isZero();
    }

    @Test
    void aBatchCarriesAtMostOneMessagePerAggregateSoEachAggregateStaysInOrder() {
        String first = "order-" + UUID.randomUUID();
        String second = "order-" + UUID.randomUUID();
        publisher.publish(new SomethingPublic(first + "/1"), first, 1);
        publisher.publish(new SomethingPublic(first + "/2"), first, 2);
        publisher.publish(new SomethingPublic(second + "/1"), second, 1);

        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(relay.relayBatch()).isZero();

        List<String> firstNotes = receive(Set.of(first, second), 3).stream()
                .filter(record -> record.key().equals(first))
                .map(record -> json.readTree(record.value()).get("data").get("note").asString())
                .toList();
        assertThat(firstNotes).containsExactly(first + "/1", first + "/2");
    }

    /** Makes marking the aggregate's row delivered fail, as a crash after the send and before the commit would. */
    private void failMarkingDelivered(String aggregateId) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION public.test_fail_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.delivered_at IS NOT NULL AND NEW.aggregate_id = TG_ARGV[0] THEN
                        RAISE EXCEPTION 'simulated crash before commit';
                    END IF;
                    RETURN NEW;
                END
                $$
                """).update();
        jdbc.sql("CREATE TRIGGER test_fail_delivery BEFORE UPDATE ON platform.outbox FOR EACH ROW "
                + "EXECUTE FUNCTION public.test_fail_delivery('" + aggregateId + "')").update();
    }

    private List<ConsumerRecord<String, String>> receive(Set<String> keys, int expected) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (received.size() < expected && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                if (keys.contains(record.key())) {
                    received.add(record);
                }
            }
        }
        return received;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private int pending() {
        return jdbc.sql("SELECT count(*) FROM platform.outbox WHERE delivered_at IS NULL").query(Integer.class).single();
    }
}
