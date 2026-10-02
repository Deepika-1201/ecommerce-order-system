package com.ecommerce.platform.messaging;

import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.platform.MessageType;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Message types and handlers that record their effects in a table, with failures on demand. */
@TestConfiguration(proxyBeanMethods = false)
class MessagingFixtures {

    static final String TOPIC = "test.integration-events";
    static final String RECORDER = "test.recorder";
    static final String AUDITOR = "test.auditor";

    @MessageType(name = "test.step-recorded")
    record StepRecorded(int step, String note) {
    }

    @MessageType(name = "test.something-public", topic = TOPIC)
    record SomethingPublic(String note) {
    }

    @MessageType(name = "test.unrouted")
    record Unrouted(String note) {
    }

    @Bean
    RecordingHandlers recordingHandlers(JdbcClient jdbc) {
        return new RecordingHandlers(jdbc);
    }

    @Bean
    Publisher publisher(Messages messages, TransactionTemplate transactions) {
        return new Publisher(messages, transactions);
    }

    /** Two consumers of {@link StepRecorded}; the recorder fails after its write when told to. */
    static class RecordingHandlers {

        private final JdbcClient jdbc;
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        RecordingHandlers(JdbcClient jdbc) {
            this.jdbc = jdbc;
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS public.test_message_effects (
                        id bigserial PRIMARY KEY,
                        consumer text NOT NULL,
                        message_id uuid NOT NULL,
                        aggregate_id text NOT NULL,
                        step integer NOT NULL)
                    """).update();
        }

        void reset() {
            failures.clear();
            jdbc.sql("TRUNCATE public.test_message_effects").update();
        }

        /** The recorder's next {@code times} deliveries for the aggregate fail after writing their effect. */
        void failNext(String aggregateId, int times) {
            failures.put(aggregateId, new AtomicInteger(times));
        }

        @HandlesMessage(consumer = RECORDER)
        void record(IncomingMessage<StepRecorded> message) {
            write(RECORDER, message);
            AtomicInteger remaining = failures.get(message.aggregateId());
            if (remaining != null && remaining.getAndDecrement() > 0) {
                throw new IllegalStateException("Injected failure after the handler's write");
            }
        }

        @HandlesMessage(consumer = AUDITOR)
        void audit(IncomingMessage<StepRecorded> message) {
            write(AUDITOR, message);
        }

        /** The steps the consumer applied for the aggregate, in the order it applied them. */
        List<Integer> steps(String consumer, String aggregateId) {
            return jdbc.sql("SELECT step FROM public.test_message_effects WHERE consumer = ? AND aggregate_id = ? "
                            + "ORDER BY id")
                    .params(consumer, aggregateId)
                    .query(Integer.class)
                    .list();
        }

        int effects(String consumer) {
            return jdbc.sql("SELECT count(*) FROM public.test_message_effects WHERE consumer = ?")
                    .param(consumer)
                    .query(Integer.class)
                    .single();
        }

        private void write(String consumer, IncomingMessage<StepRecorded> message) {
            jdbc.sql("INSERT INTO public.test_message_effects (consumer, message_id, aggregate_id, step) "
                            + "VALUES (?, ?, ?, ?)")
                    .params(consumer, message.messageId(), message.aggregateId(), message.payload().step())
                    .update();
        }
    }

    /** Publishes each message in its own committed transaction, as an aggregate's change would. */
    static class Publisher {

        private final Messages messages;
        private final TransactionTemplate transactions;

        Publisher(Messages messages, TransactionTemplate transactions) {
            this.messages = messages;
            this.transactions = transactions;
        }

        void step(String aggregateId, int step) {
            publish(new StepRecorded(step, "step " + step), aggregateId, step);
        }

        void publish(Object payload, String aggregateId, long sequence) {
            transactions.executeWithoutResult(status -> messages.publish(payload,
                    new Origin("test-aggregate", aggregateId, sequence), Correlation.start("flow-" + aggregateId)));
        }
    }
}
