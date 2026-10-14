package com.ecommerce.notifications.domain;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class NotificationMetrics {

    private final JdbcClient jdbc;

    NotificationMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        for (String state : List.of("PENDING", "SENT", "SKIPPED", "FAILED")) {
            Gauge.builder("ecom.notifications.current", this, source -> source.count(state))
                    .tag("status", state).description("Notification intents currently in this state").register(metrics);
        }
        Gauge.builder("ecom.notifications.oldest.pending.age", this, NotificationMetrics::oldestPendingAge)
                .baseUnit("seconds").description("Age of the oldest pending notification intent").register(metrics);
    }

    private double count(String state) {
        return jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE status = :status")
                .param("status", state).query(Long.class).single();
    }

    private double oldestPendingAge() {
        return jdbc.sql("""
                        SELECT coalesce(greatest(extract(epoch FROM now() - min(created_at)), 0), 0)
                        FROM notifications.notifications WHERE status = 'PENDING'
                        """).query(Double.class).single();
    }
}