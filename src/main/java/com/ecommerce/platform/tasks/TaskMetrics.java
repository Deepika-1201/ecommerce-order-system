package com.ecommerce.platform.tasks;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class TaskMetrics {

    private final JdbcClient jdbc;

    TaskMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        for (String state : List.of("PENDING", "RUNNING", "SUCCEEDED", "DEAD")) {
            Gauge.builder("ecom.tasks.current", this, source -> source.count(state))
                    .tag("status", state).description("Scheduled tasks currently in this state").register(metrics);
        }
        Gauge.builder("ecom.tasks.oldest.due.age", this, TaskMetrics::oldestDueAge)
                .baseUnit("seconds").description("Age since the oldest pending task became due").register(metrics);
        Gauge.builder("ecom.tasks.expired.leases", this, TaskMetrics::expiredLeases)
                .description("Running tasks whose leases expired and need reclaiming").register(metrics);
    }

    private double count(String state) {
        return jdbc.sql("SELECT count(*) FROM platform.scheduled_tasks WHERE status = :status")
                .param("status", state).query(Long.class).single();
    }

    private double oldestDueAge() {
        return jdbc.sql("""
                        SELECT coalesce(greatest(extract(epoch FROM now() - min(run_at)), 0), 0)
                        FROM platform.scheduled_tasks WHERE status = 'PENDING' AND run_at <= now()
                        """).query(Double.class).single();
    }

    private double expiredLeases() {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.scheduled_tasks
                        WHERE status = 'RUNNING' AND lease_until <= now()
                        """).query(Long.class).single();
    }
}