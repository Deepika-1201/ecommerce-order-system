package com.ecommerce.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
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
        limitDeadLetters("1048576");
        listeners.getListenerContainer(NotificationService.CONSUMER).stop();
        producer.close(Duration.ofSeconds(5));
        admin.close(Duration.ofSeconds(5));
    }

    @Test
    void aDuplicateBrokerDeliveryCreatesOneDurableIntentThenCommitsOffsets() throws Exception {
        UUID orderId = UUID.randomUUID();
        byte[] event = json.writeValueAsBytes(Map.of(
                "message_id", UUID.randomUUID(), "type", "order.placed", "version", 1,
                "occurred_at", "2026-10-14T10:00:00Z", "source", "ecommerce/ordering",
                "aggregate_type", "order", "aggregate_id", orderId, "sequence", 1,
                "correlation_id", orderId.toString(),
                "data", Map.of("order_id", orderId, "customer_id", UUID.randomUUID(), "order_number", "EC000000001",
                        "amount_paise", 59900, "line_count", 1)));
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