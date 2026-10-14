package com.ecommerce.platform.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class OutboxMetrics {

    private final JdbcClient jdbc;

    OutboxMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        Map.of("internal", "handler:%", "kafka", "kafka:%").forEach((channel, destination) -> {
            Gauge.builder("ecom.outbox.pending", this, source -> source.pending(destination, false))
                    .tag("channel", channel).description("Undelivered outbox rows, including parked rows")
                    .register(metrics);
            Gauge.builder("ecom.outbox.parked", this, source -> source.pending(destination, true))
                    .tag("channel", channel).description("Undelivered outbox rows requiring operator redrive")
                    .register(metrics);
            Gauge.builder("ecom.outbox.oldest.age", this, source -> source.oldestAge(destination))
                    .tag("channel", channel).baseUnit("seconds")
                    .description("Age of the oldest undelivered outbox row, including parked rows")
                    .register(metrics);
        });
    }

    private double pending(String destination, boolean parkedOnly) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.outbox
                        WHERE destination LIKE :destination AND delivered_at IS NULL
                        """ + (parkedOnly ? "AND parked_at IS NOT NULL" : ""))
                .param("destination", destination).query(Long.class).single();
    }

    private double oldestAge(String destination) {
        return jdbc.sql("""
                        SELECT coalesce(greatest(extract(epoch FROM now() - min(created_at)), 0), 0)
                        FROM platform.outbox WHERE destination LIKE :destination AND delivered_at IS NULL
                        """)
                .param("destination", destination).query(Double.class).single();
    }
}