package com.ecommerce.platform.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL for the outbox and processed-message tables (LLD §2.2–2.5). */
@Component
class OutboxRepository {

    static final String HANDLER_PREFIX = "handler:";
    static final String KAFKA_PREFIX = "kafka:";

    private static final int MAX_ERROR_LENGTH = 2_000;

    private static final String COLUMNS =
            "o.id, o.message_id, o.destination, o.aggregate_id, o.type, o.version, o.traceparent, o.envelope, o.attempts";

    // LLD §2.3: a pending row is eligible when no earlier row for its destination and aggregate is undelivered.
    private static final String ELIGIBLE = """
            o.delivered_at IS NULL AND o.parked_at IS NULL AND o.next_attempt_at <= now()
            AND NOT EXISTS (
                SELECT 1 FROM platform.outbox earlier
                WHERE earlier.destination = o.destination
                  AND earlier.aggregate_type = o.aggregate_type
                  AND earlier.aggregate_id = o.aggregate_id
                  AND earlier.delivered_at IS NULL
                  AND (earlier.sequence, earlier.id) < (o.sequence, o.id))
            """;

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String destination, Envelope envelope, String serialized) {
        jdbc.sql("""
                        INSERT INTO platform.outbox (message_id, destination, aggregate_type, aggregate_id, sequence,
                                                     type, version, traceparent, envelope, created_at, next_attempt_at)
                        VALUES (:messageId, :destination, :aggregateType, :aggregateId, :sequence,
                                :type, :version, :traceparent, :envelope, now(), now())
                        """)
                .param("messageId", envelope.messageId())
                .param("destination", destination)
                .param("aggregateType", envelope.aggregateType())
                .param("aggregateId", envelope.aggregateId())
                .param("sequence", envelope.sequence())
                .param("type", envelope.type())
                .param("version", envelope.version())
                .param("traceparent", envelope.traceparent())
                .param("envelope", serialized)
                .update();
    }

    /** Wakes the workers when the transaction commits; PostgreSQL drops the notification on rollback. */
    void notifyWorkers() {
        jdbc.sql("NOTIFY ecom_outbox").update();
    }

    /** Locks the next eligible row for one of the destinations, skipping rows other workers hold. */
    Optional<PendingMessage> claimNext(String[] destinations) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM platform.outbox o WHERE o.destination = ANY(:destinations) AND "
                        + ELIGIBLE + " ORDER BY o.next_attempt_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED")
                .param("destinations", destinations)
                .query(OutboxRepository::pendingMessage)
                .optional();
    }

    /** Locks up to {@code limit} eligible Kafka rows; the eligibility rule allows at most one per aggregate. */
    List<PendingMessage> claimKafkaBatch(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM platform.outbox o WHERE o.destination LIKE 'kafka:%' AND "
                        + ELIGIBLE + " ORDER BY o.next_attempt_at, o.id LIMIT :limit FOR UPDATE OF o SKIP LOCKED")
                .param("limit", limit)
                .query(OutboxRepository::pendingMessage)
                .list();
    }

    void markDelivered(List<Long> ids) {
        jdbc.sql("UPDATE platform.outbox SET delivered_at = now() WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .update();
    }

    /** Records that the consumer has handled the message; false if it already had. */
    boolean markProcessed(String consumer, UUID messageId) {
        return jdbc.sql("""
                        INSERT INTO platform.processed_messages (consumer, message_id, processed_at)
                        VALUES (:consumer, :messageId, now())
                        ON CONFLICT DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("messageId", messageId)
                .update() == 1;
    }

    void recordFailure(long id, String error, Duration retryAfter, boolean park) {
        jdbc.sql("""
                        UPDATE platform.outbox
                        SET attempts = attempts + 1,
                            last_error = :error,
                            next_attempt_at = now() + make_interval(secs => :retryAfterSeconds),
                            parked_at = CASE WHEN :park THEN now() END
                        WHERE id = :id AND delivered_at IS NULL
                        """)
                .param("error", error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error)
                .param("retryAfterSeconds", retryAfter.toMillis() / 1000.0)
                .param("park", park)
                .param("id", id)
                .update();
    }

    int redrive(UUID messageId) {
        return jdbc.sql("""
                        UPDATE platform.outbox
                        SET parked_at = NULL, attempts = 0, next_attempt_at = now(), last_error = NULL
                        WHERE message_id = :messageId AND parked_at IS NOT NULL AND delivered_at IS NULL
                        """)
                .param("messageId", messageId)
                .update();
    }

    private static PendingMessage pendingMessage(ResultSet row, int rowNumber) throws SQLException {
        return new PendingMessage(
                row.getLong("id"),
                row.getObject("message_id", UUID.class),
                row.getString("destination"),
                row.getString("aggregate_id"),
                row.getString("type"),
                row.getInt("version"),
                row.getString("traceparent"),
                row.getString("envelope"),
                row.getInt("attempts"));
    }
}
