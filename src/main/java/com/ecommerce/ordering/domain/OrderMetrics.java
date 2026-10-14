package com.ecommerce.ordering.domain;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class OrderMetrics {

    private final JdbcClient jdbc;

    OrderMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        for (OrderStatus state : OrderStatus.values()) {
            Gauge.builder("ecom.orders.current", this, source -> source.count(state))
                    .tag("status", state.name()).description("Orders currently in this state").register(metrics);
        }
        Gauge.builder("ecom.orders.overdue", this, OrderMetrics::overdue)
                .description("Unfinished order processes with a past deadline").register(metrics);
        Gauge.builder("ecom.orders.oldest.overdue.age", this, OrderMetrics::oldestOverdueAge)
                .baseUnit("seconds").description("Time since the oldest unfinished process deadline").register(metrics);
    }

    private double count(OrderStatus state) {
        return jdbc.sql("SELECT count(*) FROM ordering.orders WHERE status = :status")
                .param("status", state.name()).query(Long.class).single();
    }

    private double overdue() {
        return jdbc.sql("""
                        SELECT count(*) FROM ordering.order_processes
                        WHERE step <> 'DONE' AND deadline_at <= now()
                        """).query(Long.class).single();
    }

    private double oldestOverdueAge() {
        return jdbc.sql("""
                        SELECT coalesce(greatest(extract(epoch FROM now() - min(deadline_at)), 0), 0)
                        FROM ordering.order_processes WHERE step <> 'DONE' AND deadline_at <= now()
                        """).query(Double.class).single();
    }
}