package com.ecommerce.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the platform tables, so each test sees only its own messages, tasks, keys, audit entries and webhooks. */
public final class PlatformTables {

    private PlatformTables() {
    }

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("""
                TRUNCATE platform.outbox, platform.processed_messages, platform.scheduled_tasks,
                         platform.idempotency_keys, platform.audit_log, platform.webhook_inbox
                """).update();
    }
}
