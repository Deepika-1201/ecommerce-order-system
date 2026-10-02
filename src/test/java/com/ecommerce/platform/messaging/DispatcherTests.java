package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.OutboxOperations;
import com.ecommerce.platform.messaging.MessagingFixtures.Publisher;
import com.ecommerce.platform.messaging.MessagingFixtures.RecordingHandlers;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Crash-between-steps tests for in-process delivery (LLD §2.13). A handler that fails after its write stands for a
 * crash before commit: the write must roll back and the retry must apply it exactly once. The shared mapper is strict
 * here, so the forward-compatibility test checks the dispatcher's own leniency.
 */
@Import(MessagingFixtures.class)
@TestPropertySource(properties = {
    "ecom.messaging.max-attempts=3",
    "spring.jackson.deserialization.fail-on-unknown-properties=true"
})
class DispatcherTests extends IntegrationTest {

    private static final String RECORDER = MessagingFixtures.RECORDER;
    private static final String AUDITOR = MessagingFixtures.AUDITOR;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private Publisher publisher;

    @Autowired
    private RecordingHandlers handlers;

    @Autowired
    private OutboxOperations operations;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.clear(jdbc);
        handlers.reset();
    }

    @Test
    void deliversAMessageOnceToEachSubscribedHandler() {
        publisher.step("a", 1);

        deliverAllDue();
        deliverAllDue();

        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1);
        assertThat(handlers.steps(AUDITOR, "a")).containsExactly(1);
        assertThat(count("SELECT count(*) FROM platform.outbox WHERE delivered_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM platform.processed_messages")).isEqualTo(2);
    }

    @Test
    void aFailureAfterTheHandlersWriteRollsItBackAndTheRetryAppliesItOnce() {
        handlers.failNext("a", 2);
        publisher.step("a", 1);

        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).as("rolled back").isEmpty();
        assertThat(recorderRow("a")).satisfies(row -> {
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.lastError()).contains("Injected failure");
            assertThat(row.retryScheduled()).as("waits for its backoff").isTrue();
        });

        retryNow();
        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).isEmpty();

        retryNow();
        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1);
        assertThat(handlers.steps(AUDITOR, "a")).as("the other consumer was not held back").containsExactly(1);
        assertThat(recorderRow("a").delivered()).isTrue();
    }

    @Test
    void aMessageItsConsumerAlreadyProcessedIsNotHandledAgain() {
        publisher.step("a", 1);
        UUID messageId = recorderRow("a").messageId();
        jdbc.sql("INSERT INTO platform.processed_messages (consumer, message_id, processed_at) VALUES (?, ?, now())")
                .params(RECORDER, messageId)
                .update();

        deliverAllDue();

        assertThat(handlers.steps(RECORDER, "a")).isEmpty();
        assertThat(recorderRow("a").delivered()).isTrue();
    }

    @Test
    void anAggregatesMessagesArriveInSequenceOrderEvenWhenCommittedOutOfOrder() {
        publisher.step("a", 2);
        publisher.step("a", 1);
        publisher.step("a", 3);

        deliverAllDue();

        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1, 2, 3);
    }

    @Test
    void aFailingMessageHoldsBackOnlyItsOwnAggregate() {
        handlers.failNext("a", 1);
        publisher.step("a", 1);
        publisher.step("a", 2);
        publisher.step("b", 1);

        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).isEmpty();
        assertThat(handlers.steps(RECORDER, "b")).containsExactly(1);

        retryNow();
        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1, 2);
    }

    @Test
    void aMessageIsParkedAfterItsLastAttemptAndRedrivenByAnOperator() {
        handlers.failNext("a", 3);
        publisher.step("a", 1);
        publisher.step("a", 2);

        for (int attempt = 1; attempt <= 3; attempt++) {
            deliverAllDue();
            retryNow();
        }
        OutboxRowState parked = recorderRow("a");
        assertThat(parked.parked()).isTrue();
        assertThat(parked.attempts()).isEqualTo(3);
        deliverAllDue();
        assertThat(handlers.steps(RECORDER, "a")).as("the parked message holds back its aggregate").isEmpty();

        assertThat(operations.redrive(parked.messageId())).isEqualTo(1);
        deliverAllDue();

        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1, 2);
    }

    @Test
    void aMessageWithFieldsFromANewerVersionIsDelivered() {
        jdbc.sql("""
                INSERT INTO platform.outbox (message_id, destination, aggregate_type, aggregate_id, sequence, type,
                                             version, envelope, created_at, next_attempt_at)
                VALUES (?, ?, 'test-aggregate', 'a', 1, 'test.step-recorded', 1, ?, now(), now())
                """).params(UUID.randomUUID(), "handler:" + RECORDER, """
                {"message_id": "0199a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b", "type": "test.step-recorded", "version": 1,
                 "occurred_at": "2026-10-02T10:00:00Z", "source": "ecommerce/platform",
                 "aggregate_type": "test-aggregate", "aggregate_id": "a", "sequence": 1, "correlation_id": "a",
                 "added_to_the_envelope_later": true,
                 "data": {"step": 1, "note": "n", "added_to_the_payload_later": [1, 2]}}
                """).update();

        deliverAllDue();

        assertThat(handlers.steps(RECORDER, "a")).containsExactly(1);
    }

    @Test
    void anUnreadableMessageIsParkedAtOnce() {
        jdbc.sql("""
                INSERT INTO platform.outbox (message_id, destination, aggregate_type, aggregate_id, sequence, type,
                                             version, envelope, created_at, next_attempt_at)
                VALUES (?, ?, 'test-aggregate', 'a', 1, 'test.step-recorded', 1, '{"data": {"step": "x"', now(), now())
                """).params(UUID.randomUUID(), "handler:" + RECORDER).update();

        deliverAllDue();

        OutboxRowState row = recorderRow("a");
        assertThat(row.parked()).isTrue();
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.lastError()).isNotBlank();
    }

    @Test
    void concurrentWorkersDeliverEveryMessageExactlyOnceAndInOrder() throws Exception {
        List<String> aggregates = IntStream.range(0, 20).mapToObj(index -> "agg-" + index).toList();
        for (int step = 1; step <= 10; step++) {
            for (String aggregate : aggregates) {
                publisher.step(aggregate, step);
            }
        }

        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> running = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            for (int worker = 0; worker < 4; worker++) {
                running.add(workers.submit(() -> {
                    while (pending() > 0 && System.nanoTime() < deadline) {
                        if (!dispatcher.deliverNext()) {
                            Thread.onSpinWait();
                        }
                    }
                }));
            }
            for (Future<?> worker : running) {
                worker.get();
            }
        }

        assertThat(pending()).isZero();
        for (String aggregate : aggregates) {
            assertThat(handlers.steps(RECORDER, aggregate)).as(aggregate).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(handlers.steps(AUDITOR, aggregate)).as(aggregate).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }
        assertThat(handlers.effects(RECORDER) + handlers.effects(AUDITOR)).isEqualTo(400);
    }

    private void deliverAllDue() {
        while (dispatcher.deliverNext()) {
            // until nothing is due
        }
    }

    private void retryNow() {
        jdbc.sql("UPDATE platform.outbox SET next_attempt_at = now() WHERE delivered_at IS NULL AND parked_at IS NULL")
                .update();
    }

    private int pending() {
        return count("SELECT count(*) FROM platform.outbox WHERE delivered_at IS NULL");
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    /** The recorder's row for the aggregate's first message. */
    private OutboxRowState recorderRow(String aggregateId) {
        return jdbc.sql("""
                        SELECT message_id, attempts, last_error, next_attempt_at > now() AS retry_scheduled,
                               parked_at IS NOT NULL AS parked, delivered_at IS NOT NULL AS delivered
                        FROM platform.outbox
                        WHERE destination = ? AND aggregate_id = ?
                        ORDER BY sequence, id
                        LIMIT 1
                        """)
                .params("handler:" + RECORDER, aggregateId)
                .query(OutboxRowState.class)
                .single();
    }

    record OutboxRowState(
            UUID messageId, int attempts, String lastError, boolean retryScheduled, boolean parked,
            boolean delivered) {
    }
}
