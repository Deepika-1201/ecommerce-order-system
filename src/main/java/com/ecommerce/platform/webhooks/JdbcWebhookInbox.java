package com.ecommerce.platform.webhooks;

import com.ecommerce.platform.ReceivedWebhook;
import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.platform.WebhookInbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcWebhookInbox implements WebhookInbox {

    private final JdbcClient jdbc;
    private final TaskScheduler tasks;

    JdbcWebhookInbox(JdbcClient jdbc, TaskScheduler tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean store(ReceivedWebhook webhook, String body, String taskType) {
        boolean stored = jdbc.sql("""
                        INSERT INTO platform.webhook_inbox (source, event_id, type, body, received_at)
                        VALUES (:source, :eventId, :type, :body, now())
                        ON CONFLICT (source, event_id) DO NOTHING
                        """)
                .param("source", webhook.source())
                .param("eventId", webhook.eventId())
                .param("type", webhook.type())
                .param("body", body)
                .update() == 1;
        if (stored) {
            tasks.schedule(TaskRequest.of(taskType, webhook)
                    .dedupeKey("webhook:" + webhook.source() + ":" + webhook.eventId()));
        }
        return stored;
    }

    @Override
    public String body(ReceivedWebhook webhook) {
        return jdbc.sql("SELECT body FROM platform.webhook_inbox WHERE source = :source AND event_id = :eventId")
                .param("source", webhook.source())
                .param("eventId", webhook.eventId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("No webhook " + webhook.eventId() + " from "
                        + webhook.source() + " in the inbox"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(ReceivedWebhook webhook) {
        jdbc.sql("""
                        UPDATE platform.webhook_inbox SET processed_at = now()
                        WHERE source = :source AND event_id = :eventId AND processed_at IS NULL
                        """)
                .param("source", webhook.source())
                .param("eventId", webhook.eventId())
                .update();
    }
}
