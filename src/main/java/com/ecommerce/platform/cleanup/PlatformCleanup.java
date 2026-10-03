package com.ecommerce.platform.cleanup;

import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Deletes finished platform records in small batches, so no statement holds locks for long (LLD §2.12). */
@Component
class PlatformCleanup {

    static final String TASK_TYPE = "platform.cleanup";

    private static final Logger log = LoggerFactory.getLogger(PlatformCleanup.class);
    private static final int BATCH_SIZE = 1_000;

    private final JdbcClient jdbc;
    private final CleanupProperties properties;

    PlatformCleanup(JdbcClient jdbc, CleanupProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @HandlesTask(type = TASK_TYPE, every = "1h")
    void run(TaskExecution<Void> task) {
        int messages = deleteInBatches("""
                DELETE FROM platform.outbox WHERE id IN (
                    SELECT id FROM platform.outbox
                    WHERE delivered_at < now() - make_interval(secs => :ageSeconds)
                    LIMIT :batchSize)
                """, properties.deliveredMessages());
        int processed = deleteInBatches("""
                DELETE FROM platform.processed_messages WHERE (consumer, message_id) IN (
                    SELECT consumer, message_id FROM platform.processed_messages
                    WHERE processed_at < now() - make_interval(secs => :ageSeconds)
                    LIMIT :batchSize)
                """, properties.processedMessages());
        int keys = deleteInBatches("""
                DELETE FROM platform.idempotency_keys WHERE (scope, key) IN (
                    SELECT scope, key FROM platform.idempotency_keys
                    WHERE expires_at < now() - make_interval(secs => :ageSeconds)
                    LIMIT :batchSize)
                """, Duration.ZERO);
        int tasks = deleteInBatches("""
                DELETE FROM platform.scheduled_tasks WHERE id IN (
                    SELECT id FROM platform.scheduled_tasks
                    WHERE status IN ('SUCCEEDED', 'DEAD') AND updated_at < now() - make_interval(secs => :ageSeconds)
                    LIMIT :batchSize)
                """, properties.finishedTasks());
        int webhooks = deleteInBatches("""
                DELETE FROM platform.webhook_inbox WHERE (source, event_id) IN (
                    SELECT source, event_id FROM platform.webhook_inbox
                    WHERE processed_at < now() - make_interval(secs => :ageSeconds)
                    LIMIT :batchSize)
                """, properties.processedWebhooks());
        log.info("Cleanup deleted {} delivered messages, {} processed-message records, {} idempotency keys, {} "
                + "finished tasks and {} processed webhooks", messages, processed, keys, tasks, webhooks);
    }

    private int deleteInBatches(String sql, Duration age) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("ageSeconds", age.toSeconds()).param("batchSize", BATCH_SIZE).update();
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
