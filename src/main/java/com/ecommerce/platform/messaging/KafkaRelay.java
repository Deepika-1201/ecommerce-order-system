package com.ecommerce.platform.messaging;

import com.ecommerce.platform.WorkerComponent;
import com.ecommerce.platform.workers.Backoff;
import com.ecommerce.platform.workers.Wakeups;
import com.ecommerce.platform.workers.WorkerLoop;
import com.ecommerce.platform.workers.WorkerProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays Kafka rows from the outbox in batches (LLD §2.5): claim, send, wait for every acknowledgement, mark
 * delivered, commit. A crash after sending sends again, so delivery is at least once and consumers deduplicate on
 * {@code message_id}. Failures back off but never park: they come from the broker, not the message.
 */
@WorkerComponent
class KafkaRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final MessagingProperties properties;
    private final WorkerProperties workers;
    private final WorkerLoop loop;

    KafkaRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
            MessagingProperties properties, WorkerProperties workers, Wakeups wakeups) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.properties = properties;
        this.workers = workers;
        this.loop = new WorkerLoop("kafka-relay", 1, wakeups.of(Wakeups.OUTBOX), properties.pollInterval(),
                () -> relayBatch() > 0);
    }

    @Override
    public void start() {
        if (workers.autostart()) {
            loop.start();
        }
    }

    @Override
    public void stop() {
        loop.stop(Duration.ofSeconds(15));
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    /** Relays one batch; returns how many rows it claimed, delivered or not. */
    int relayBatch() {
        List<PendingMessage> claimed = new ArrayList<>();
        try {
            Integer relayed = transactions.execute(status -> {
                List<PendingMessage> batch = outbox.claimKafkaBatch(properties.relayBatchSize());
                claimed.addAll(batch);
                if (batch.isEmpty()) {
                    return 0;
                }
                sendAll(batch);
                outbox.markDelivered(batch.stream().map(PendingMessage::id).toList());
                return batch.size();
            });
            return relayed == null ? 0 : relayed;
        } catch (RuntimeException e) {
            if (claimed.isEmpty()) {
                throw e;
            }
            recordFailures(claimed, e);
            return claimed.size();
        }
    }

    private void sendAll(List<PendingMessage> batch) {
        CompletableFuture<?>[] acknowledgements = batch.stream().map(this::send).toArray(CompletableFuture[]::new);
        Duration timeout = properties.relaySendTimeout();
        try {
            CompletableFuture.allOf(acknowledgements).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new RelayFailedException("Kafka rejected a message", e.getCause());
        } catch (TimeoutException e) {
            throw new RelayFailedException("Kafka did not acknowledge the batch within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RelayFailedException("Interrupted while waiting for Kafka", e);
        }
    }

    private CompletableFuture<?> send(PendingMessage message) {
        String topic = message.destination().substring(OutboxRepository.KAFKA_PREFIX.length());
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, message.aggregateId(), message.envelope());
        record.headers()
                .add("type", message.type().getBytes(StandardCharsets.UTF_8))
                .add("version", Integer.toString(message.version()).getBytes(StandardCharsets.UTF_8));
        if (message.traceparent() != null) {
            record.headers().add("traceparent", message.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        return kafka.send(record);
    }

    private void recordFailures(List<PendingMessage> batch, RuntimeException failure) {
        String error = NestedExceptionUtils.getMostSpecificCause(failure).toString();
        log.warn("Relaying {} messages to Kafka failed; they will be retried", batch.size(), failure);
        transactions.executeWithoutResult(status -> batch.forEach(message -> outbox.recordFailure(message.id(),
                error,
                Backoff.delay(message.attempts() + 1, properties.initialBackoff(), properties.maxBackoff()),
                false)));
    }

    static class RelayFailedException extends RuntimeException {

        RelayFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
