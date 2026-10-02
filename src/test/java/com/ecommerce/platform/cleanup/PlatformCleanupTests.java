package com.ecommerce.platform.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.TaskExecution;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PlatformCleanupTests extends IntegrationTest {

    @Autowired
    private PlatformCleanup cleanup;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTables() {
        PlatformTables.clear(jdbc);
    }

    @Test
    void deletesOnlyFinishedRecordsPastTheirRetention() {
        outboxRow("old-delivered", "now() - interval '8 days'", "now() - interval '8 days'");
        outboxRow("recent-delivered", "now() - interval '8 days'", "now() - interval '6 days'");
        outboxRow("old-pending", "now() - interval '8 days'", "NULL");
        processedMessage("old-consumer", "now() - interval '31 days'");
        processedMessage("recent-consumer", "now() - interval '29 days'");
        idempotencyKey("expired", "now() - interval '1 minute'");
        idempotencyKey("valid", "now() + interval '1 hour'");
        task("old-succeeded", "SUCCEEDED", "now() - interval '31 days'");
        task("old-dead", "DEAD", "now() - interval '31 days'");
        task("recent-succeeded", "SUCCEEDED", "now() - interval '29 days'");
        task("old-pending", "PENDING", "now() - interval '31 days'");

        cleanup.run(new TaskExecution<>(UUID.randomUUID(), PlatformCleanup.TASK_TYPE, 1, null, null));

        assertThat(values("SELECT aggregate_id FROM platform.outbox"))
                .containsExactlyInAnyOrder("recent-delivered", "old-pending");
        assertThat(values("SELECT consumer FROM platform.processed_messages")).containsExactly("recent-consumer");
        assertThat(values("SELECT key FROM platform.idempotency_keys")).containsExactly("valid");
        assertThat(values("SELECT dedupe_key FROM platform.scheduled_tasks"))
                .containsExactlyInAnyOrder("recent-succeeded", "old-pending");
    }

    @Test
    void deletesInBatchesUntilNothingIsLeft() {
        jdbc.sql("""
                INSERT INTO platform.processed_messages (consumer, message_id, processed_at)
                SELECT 'bulk', gen_random_uuid(), now() - interval '40 days' FROM generate_series(1, 2500)
                """).update();

        cleanup.run(new TaskExecution<>(UUID.randomUUID(), PlatformCleanup.TASK_TYPE, 1, null, null));

        assertThat(values("SELECT consumer FROM platform.processed_messages")).isEmpty();
    }

    private void outboxRow(String aggregateId, String createdAt, String deliveredAt) {
        jdbc.sql("INSERT INTO platform.outbox (message_id, destination, aggregate_type, aggregate_id, sequence, type, "
                        + "version, envelope, created_at, next_attempt_at, delivered_at) "
                        + "VALUES (?, 'handler:test', 'test', ?, 1, 'test.type', 1, '{}', " + createdAt + ", "
                        + createdAt + ", " + deliveredAt + ")")
                .params(UUID.randomUUID(), aggregateId)
                .update();
    }

    private void processedMessage(String consumer, String processedAt) {
        jdbc.sql("INSERT INTO platform.processed_messages (consumer, message_id, processed_at) VALUES (?, ?, "
                        + processedAt + ")")
                .params(consumer, UUID.randomUUID())
                .update();
    }

    private void idempotencyKey(String key, String expiresAt) {
        jdbc.sql("INSERT INTO platform.idempotency_keys (scope, key, fingerprint, created_at, expires_at) "
                        + "VALUES ('customer', ?, 'f', now() - interval '1 day', " + expiresAt + ")")
                .param(key)
                .update();
    }

    private void task(String dedupeKey, String status, String updatedAt) {
        jdbc.sql("INSERT INTO platform.scheduled_tasks (id, type, dedupe_key, payload, status, run_at, attempts, "
                        + "max_attempts, created_at, updated_at) "
                        + "VALUES (?, 'test.type', ?, 'null', ?, now(), 1, 10, " + updatedAt + ", " + updatedAt + ")")
                .params(UUID.randomUUID(), dedupeKey, status)
                .update();
    }

    private List<String> values(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }
}
