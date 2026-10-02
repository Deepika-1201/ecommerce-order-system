package com.ecommerce.platform.messaging;

import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.WorkerComponent;
import com.ecommerce.platform.messaging.MessageHandlerRegistry.RegisteredHandler;
import com.ecommerce.platform.workers.Backoff;
import com.ecommerce.platform.workers.Wakeups;
import com.ecommerce.platform.workers.WorkerLoop;
import com.ecommerce.platform.workers.WorkerProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers outbox rows to this instance's handlers, one transaction per delivery (LLD §2.4): claim, record the
 * processed message, run the handler, mark delivered. A failure rolls all of it back, then the row is retried with
 * backoff and parked after too many attempts.
 */
@WorkerComponent
class Dispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final OutboxRepository outbox;
    private final MessageHandlerRegistry handlers;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final MessagingProperties properties;
    private final WorkerProperties workers;
    private final WorkerLoop loop;

    Dispatcher(OutboxRepository outbox, MessageHandlerRegistry handlers, JsonMapper json,
            TransactionTemplate transactions, MessagingProperties properties, WorkerProperties workers,
            Wakeups wakeups) {
        this.outbox = outbox;
        this.handlers = handlers;
        this.json = json;
        this.transactions = transactions;
        this.properties = properties;
        this.workers = workers;
        this.loop = new WorkerLoop("dispatcher", properties.dispatcherConcurrency(), wakeups.of(Wakeups.OUTBOX),
                properties.pollInterval(), this::deliverNext);
    }

    @Override
    public void start() {
        if (workers.autostart()) {
            loop.start();
        }
    }

    @Override
    public void stop() {
        loop.stop(Duration.ofSeconds(10));
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    /** Delivers the next eligible message, if any; returns whether there was one. */
    boolean deliverNext() {
        String[] destinations = handlers.handlerDestinations();
        if (destinations.length == 0) {
            return false;
        }
        AtomicReference<PendingMessage> claimed = new AtomicReference<>();
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                PendingMessage message = outbox.claimNext(destinations).orElse(null);
                if (message == null) {
                    return false;
                }
                claimed.set(message);
                deliver(message);
                outbox.markDelivered(List.of(message.id()));
                return true;
            }));
        } catch (RuntimeException e) {
            PendingMessage message = claimed.get();
            if (message == null) {
                throw e;
            }
            recordFailure(message, e);
            return true;
        }
    }

    private void deliver(PendingMessage pending) {
        RegisteredHandler handler = handlers.handlerFor(pending.destination());
        IncomingMessage<?> message = read(pending, handler.payloadType());
        if (!outbox.markProcessed(handler.consumer(), pending.messageId())) {
            log.debug("Message {} was already processed by {}", pending.messageId(), handler.consumer());
            return;
        }
        try (var _ = MDC.putCloseable("correlation_id", message.correlationId());
                var _ = MDC.putCloseable("message_id", message.messageId().toString())) {
            handler.invoke(message);
        }
    }

    private IncomingMessage<?> read(PendingMessage pending, Class<?> payloadType) {
        try {
            Envelope envelope = json.readerFor(Envelope.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(pending.envelope());
            Object payload = json.readerFor(payloadType)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(envelope.data());
            return new IncomingMessage<>(envelope.messageId(), envelope.type(), envelope.version(),
                    envelope.occurredAt(), envelope.source(), envelope.aggregateType(), envelope.aggregateId(),
                    envelope.sequence(), envelope.correlationId(), envelope.causationId(), envelope.traceparent(),
                    payload);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new UnreadableMessageException(pending.messageId(), e);
        }
    }

    private void recordFailure(PendingMessage message, RuntimeException failure) {
        int attempts = message.attempts() + 1;
        boolean park = failure instanceof UnreadableMessageException || attempts >= properties.maxAttempts();
        Duration retryAfter = Backoff.delay(attempts, properties.initialBackoff(), properties.maxBackoff());
        String error = NestedExceptionUtils.getMostSpecificCause(failure).toString();
        if (park) {
            log.error("Parked message {} for {} after {} attempts", message.messageId(), message.destination(),
                    attempts, failure);
        } else {
            log.warn("Delivery of message {} to {} failed (attempt {}); retrying in {}", message.messageId(),
                    message.destination(), attempts, retryAfter, failure);
        }
        transactions.executeWithoutResult(status -> outbox.recordFailure(message.id(), error, retryAfter, park));
    }
}
