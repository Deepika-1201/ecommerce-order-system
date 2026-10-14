package com.ecommerce.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecommerce.notifications.EmailDelivery;
import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@EmbeddedKafka(partitions = 1, topics = {IntegrationEvents.ORDER_TOPIC, NotificationKafkaTests.DLT})
@TestPropertySource(properties = {"ecom.events.enabled=true", "ecom.events.partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@DirtiesContext
class NotificationKafkaTests extends IntegrationTest {

    static final String DLT = IntegrationEvents.ORDER_TOPIC + ".ecom-notifications.dlt";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private OrderNotificationListener notificationListener;

    @MockitoBean
    private EmailDelivery email;

    private Producer<byte[], byte[]> producer;
    private Admin admin;

    @BeforeEach
    void start() {
        jdbc.sql("TRUNCATE notifications.notifications, notifications.order_progress").update();
        PlatformTables.clear(jdbc);
        producer = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new ByteArraySerializer(), new ByteArraySerializer());
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
        var listener = listeners.getListenerContainer(NotificationService.CONSUMER);
        assertThat(listener).isNotNull();
        listener.start();
        ContainerTestUtils.waitForAssignment(listener, 1);
    }

    @AfterEach
    void stop() throws Exception {
        jdbc.sql("DROP TRIGGER IF EXISTS test_block_broker_notification ON platform.scheduled_tasks").update();
        limitDeadLetters("1048576");
        listeners.getListenerContainer(NotificationService.CONSUMER).stop();
        producer.close(Duration.ofSeconds(5));
        admin.close(Duration.ofSeconds(5));
    }

    @Test
    void aDuplicateBrokerDeliveryCreatesOneDurableIntentThenCommitsOffsets() throws Exception {
        UUID orderId = UUID.randomUUID();
        byte[] event = placedEvent(orderId);
        send(bytes(orderId.toString()), event);
        RecordMetadata duplicate = send(bytes(orderId.toString()), event);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(count("notifications.notifications")).isEqualTo(1);
            assertThat(count("platform.scheduled_tasks")).isEqualTo(1);
            assertThat(committed()).isEqualTo(duplicate.offset() + 1);
        });
        verifyNoInteractions(email);
    }

    @Test
    void poisonMessagesKeepTheirExactKeyAndValueInTheDeadLetterTopic() throws Exception {
        byte[] key = {(byte) 0xff, 42};
        byte[] poison = {(byte) 0xc3, (byte) 0x28};
        try (Consumer<byte[], byte[]> deadLetters = deadLetters()) {
            RecordMetadata source = send(key, poison);
            ConsumerRecord<byte[], byte[]> recovered = nextDeadLetter(deadLetters, key);

            assertThat(recovered.key()).containsExactly(key);
            assertThat(recovered.value()).containsExactly(poison);
            assertThat(recovered.partition()).isZero();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(committed()).isEqualTo(source.offset() + 1));
            assertThat(count("platform.processed_messages")).isZero();
            assertThat(count("notifications.notifications")).isZero();
        }
    }

    @Test
    void failedDeadLetterPublicationDoesNotCommitTheSourceOffsetAndRecoversAfterTheFix() throws Exception {
        byte[] key = bytes("poison-" + UUID.randomUUID());
        double failuresBefore = metrics.get("ecom.kafka.deadletters.failures")
                .tag("consumer", NotificationService.CONSUMER).counter().count();
        try (Consumer<byte[], byte[]> deadLetters = deadLetters()) {
            limitDeadLetters("1");
            RecordMetadata source = send(key, bytes("not-json"));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(metrics.get("ecom.kafka.deadletters.failures")
                            .tag("consumer", NotificationService.CONSUMER).counter().count())
                            .isGreaterThan(failuresBefore));
            assertThat(committed()).isLessThanOrEqualTo(source.offset());

            limitDeadLetters("1048576");
            assertThat(nextDeadLetter(deadLetters, key).value()).containsExactly(bytes("not-json"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(committed()).isEqualTo(source.offset() + 1));
        }
    }

    @Test
    void aConsumerRestartAfterDatabaseCommitBeforeAcknowledgementDoesNotRepeatTheEffect() throws Exception {
        listeners.getListenerContainer(NotificationService.CONSUMER).stop();
        UUID orderId = UUID.randomUUID();
        byte[] key = bytes(orderId.toString());
        byte[] event = placedEvent(orderId);
        RecordMetadata source = send(key, event);
        TopicPartition partition = new TopicPartition(source.topic(), source.partition());
        UUID notificationId;
        UUID taskId;
        try (Consumer<byte[], byte[]> first = manualSourceConsumer()) {
            first.seek(partition, source.offset());
            first.commitSync(Map.of(partition, new OffsetAndMetadata(source.offset())));
            ConsumerRecord<byte[], byte[]> original = KafkaTestUtils.getSingleRecord(first,
                    IntegrationEvents.ORDER_TOPIC, Duration.ofSeconds(10));
            assertThat(original.offset()).isEqualTo(source.offset());

            assertThatThrownBy(() -> notificationListener.receive(original, () -> {
                throw new IllegalStateException("simulated crash before offset commit");
            })).isInstanceOf(IllegalStateException.class).hasMessage("simulated crash before offset commit");

            assertThat(count("notifications.notifications")).isEqualTo(1);
            assertThat(count("platform.scheduled_tasks")).isEqualTo(1);
            assertThat(committed()).isEqualTo(source.offset());
            notificationId = jdbc.sql("SELECT id FROM notifications.notifications").query(UUID.class).single();
            taskId = jdbc.sql("SELECT id FROM platform.scheduled_tasks").query(UUID.class).single();
        }

        try (Consumer<byte[], byte[]> restarted = manualSourceConsumer()) {
            ConsumerRecord<byte[], byte[]> redelivered = KafkaTestUtils.getSingleRecord(restarted,
                    IntegrationEvents.ORDER_TOPIC, Duration.ofSeconds(10));
            assertThat(redelivered.offset()).isEqualTo(source.offset());
            assertThat(redelivered.key()).containsExactly(key);
            assertThat(redelivered.value()).containsExactly(event);

            notificationListener.receive(redelivered, () -> restarted.commitSync(
                    Map.of(partition, new OffsetAndMetadata(redelivered.offset() + 1))));
        }

        assertThat(committed()).isEqualTo(source.offset() + 1);
        assertThat(jdbc.sql("SELECT id FROM notifications.notifications").query(UUID.class).list())
                .containsExactly(notificationId);
        assertThat(jdbc.sql("SELECT id FROM platform.scheduled_tasks").query(UUID.class).list())
                .containsExactly(taskId);
        assertThat(count("platform.processed_messages")).isEqualTo(1);
        verifyNoInteractions(email);
    }

    @Test
    void aFailedDatabaseEffectCanBeReplayedFromDeadLettersAfterRepair() throws Exception {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION public.test_block_broker_notification() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated notification database failure';
                END
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_block_broker_notification BEFORE INSERT ON platform.scheduled_tasks
                FOR EACH ROW EXECUTE FUNCTION public.test_block_broker_notification()
                """).update();
        UUID orderId = UUID.randomUUID();
        byte[] key = bytes(orderId.toString());
        byte[] event = placedEvent(orderId);
        try (Consumer<byte[], byte[]> deadLetters = deadLetters()) {
            RecordMetadata source = send(key, event);
            ConsumerRecord<byte[], byte[]> recovered = nextDeadLetter(deadLetters, key);
            assertThat(recovered.key()).containsExactly(key);
            assertThat(recovered.value()).containsExactly(event);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(committed()).isEqualTo(source.offset() + 1));
            assertThat(count("notifications.notifications")).isZero();
            assertThat(count("notifications.order_progress")).isZero();
            assertThat(count("platform.processed_messages")).isZero();
            assertThat(count("platform.scheduled_tasks")).isZero();

            jdbc.sql("DROP TRIGGER test_block_broker_notification ON platform.scheduled_tasks").update();
            send(recovered.key(), recovered.value());
            RecordMetadata repeatedReplay = send(recovered.key(), recovered.value());

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(committed()).isEqualTo(repeatedReplay.offset() + 1);
                assertThat(count("notifications.notifications")).isEqualTo(1);
                assertThat(count("platform.processed_messages")).isEqualTo(1);
                assertThat(count("platform.scheduled_tasks")).isEqualTo(1);
            });
            verifyNoInteractions(email);
        }
    }

    private Consumer<byte[], byte[]> manualSourceConsumer() {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(broker, NotificationService.CONSUMER, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        Consumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties,
                new ByteArrayDeserializer(), new ByteArrayDeserializer());
        consumer.assign(List.of(new TopicPartition(IntegrationEvents.ORDER_TOPIC, 0)));
        return consumer;
    }

    private byte[] placedEvent(UUID orderId) {
        return json.writeValueAsBytes(Map.of(
                "message_id", UUID.randomUUID(), "type", "order.placed", "version", 1,
                "occurred_at", "2026-10-14T10:00:00Z", "source", "ecommerce/ordering",
                "aggregate_type", "order", "aggregate_id", orderId, "sequence", 1,
                "correlation_id", orderId.toString(),
                "data", Map.of("order_id", orderId, "customer_id", UUID.randomUUID(), "order_number", "EC000000001",
                        "amount_paise", 59900, "line_count", 1)));
    }

    private RecordMetadata send(byte[] key, byte[] value) throws Exception {
        return producer.send(new ProducerRecord<>(IntegrationEvents.ORDER_TOPIC, 0, key, value))
                .get(10, TimeUnit.SECONDS);
    }

    private Consumer<byte[], byte[]> deadLetters() {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(broker, "dlt-test-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties,
                new ByteArrayDeserializer(), new ByteArrayDeserializer());
        broker.consumeFromAnEmbeddedTopic(consumer, DLT);
        return consumer;
    }

    private ConsumerRecord<byte[], byte[]> nextDeadLetter(Consumer<byte[], byte[]> consumer, byte[] key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(100))) {
                if (java.util.Arrays.equals(record.key(), key)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No matching dead letter arrived");
    }

    private void limitDeadLetters(String bytes) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, DLT);
        var change = new AlterConfigOp(new ConfigEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, bytes),
                AlterConfigOp.OpType.SET);
        admin.incrementalAlterConfigs(Map.of(resource, List.of(change))).all().get(10, TimeUnit.SECONDS);
    }

    private long committed() throws Exception {
        var offsets = admin.listConsumerGroupOffsets(NotificationService.CONSUMER)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(IntegrationEvents.ORDER_TOPIC, 0));
        return offset == null ? -1 : offset.offset();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}