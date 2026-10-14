package com.ecommerce.platform.messaging;

import com.ecommerce.platform.ConditionalOnRole;
import com.ecommerce.platform.IntegrationEvents;
import com.ecommerce.platform.InvalidIntegrationEventException;
import com.ecommerce.platform.Role;
import com.ecommerce.platform.workers.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(Role.WORKER)
@ConditionalOnProperty(name = "ecom.events.enabled", havingValue = "true")
class IntegrationKafkaConfiguration {

    @Bean
    NewTopics integrationTopics(IntegrationEventProperties settings) {
        String minimumIsr = Integer.toString(settings.minInSyncReplicas());
        return new NewTopics(
                TopicBuilder.name(IntegrationEvents.ORDER_TOPIC).partitions(settings.partitions())
                        .replicas(settings.replicationFactor()).config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minimumIsr)
                        .config(TopicConfig.RETENTION_MS_CONFIG, "604800000").build(),
                TopicBuilder.name(IntegrationEvents.STOCK_TOPIC).partitions(settings.partitions())
                        .replicas(settings.replicationFactor()).config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minimumIsr)
                        .compact().build(),
                TopicBuilder.name(IntegrationEvents.ORDER_TOPIC + ".ecom-notifications.dlt").partitions(1)
                        .replicas(settings.replicationFactor()).config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minimumIsr)
                        .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000").build(),
                TopicBuilder.name(IntegrationEvents.STOCK_TOPIC + ".ecom-catalog-availability.dlt").partitions(1)
                        .replicas(settings.replicationFactor()).config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minimumIsr)
                        .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000").build());
    }

    @Bean(destroyMethod = "close")
    DeadLetters integrationDeadLetters(KafkaProperties kafka) {
        return new DeadLetters(kafka.buildProducerProperties());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<byte[], byte[]> notificationsKafkaFactory(KafkaProperties kafka,
            DeadLetters deadLetters, WorkerProperties workers, MeterRegistry metrics) {
        return factory("ecom-notifications", kafka, deadLetters, workers, metrics);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<byte[], byte[]> availabilityKafkaFactory(KafkaProperties kafka,
            DeadLetters deadLetters, WorkerProperties workers, MeterRegistry metrics) {
        return factory("ecom-catalog-availability", kafka, deadLetters, workers, metrics);
    }

    private ConcurrentKafkaListenerContainerFactory<byte[], byte[]> factory(String group, KafkaProperties kafka,
            DeadLetters deadLetters, WorkerProperties workers, MeterRegistry metrics) {
        Map<String, Object> consumer = new HashMap<>(kafka.buildConsumerProperties());
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        var factory = new ConcurrentKafkaListenerContainerFactory<byte[], byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer,
                new ByteArrayDeserializer(), new ByteArrayDeserializer()));
        factory.setAutoStartup(workers.autostart());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(250);
        var recoverer = new DeadLetterPublishingRecoverer(deadLetters.template,
                (record, failure) -> new TopicPartition(record.topic() + "." + group + ".dlt", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setAppendOriginalHeaders(false);
        Counter published = metrics.counter("ecom.kafka.deadletters.published", "consumer", group);
        Counter failures = metrics.counter("ecom.kafka.deadletters.failures", "consumer", group);
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1_000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(4_000);
        var errors = new DefaultErrorHandler((record, failure) -> {
            try {
                recoverer.accept(record, failure);
                published.increment();
            } catch (RuntimeException rejected) {
                failures.increment();
                throw rejected;
            }
        }, backoff);
        errors.addNotRetryableExceptions(InvalidIntegrationEventException.class);
        errors.setCommitRecovered(true);
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    static final class DeadLetters implements AutoCloseable {

        private final DefaultKafkaProducerFactory<byte[], byte[]> producer;
        private final KafkaTemplate<byte[], byte[]> template;

        DeadLetters(Map<String, Object> properties) {
            producer = new DefaultKafkaProducerFactory<>(properties, new ByteArraySerializer(), new ByteArraySerializer());
            template = new KafkaTemplate<>(producer);
        }

        @Override
        public void close() {
            producer.destroy();
        }
    }
}