package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.ordering.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderMetricsTests extends OrderingTest {

    @Autowired
    private MeterRegistry metrics;

    @Test
    void allStatesExistAtZeroBeforeTheFirstOrder() {
        for (OrderStatus state : OrderStatus.values()) {
            assertThat(metrics.get("ecom.orders.current").tag("status", state.name()).gauge().value()).isZero();
        }
        assertThat(metrics.get("ecom.orders.overdue").gauge().value()).isZero();
        assertThat(metrics.get("ecom.orders.oldest.overdue.age").gauge().value()).isZero();
    }

    @Test
    void currentStateAndOverdueGaugesFollowTheCommittedOrder() {
        UUID orderId = awaitingPayment(product("Metrics", 50_000, 3), 1);
        assertThat(metrics.get("ecom.orders.current").tag("status", "AWAITING_PAYMENT").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("ecom.orders.current").tag("status", "PLACED").gauge().value()).isZero();
        assertThat(metrics.get("ecom.orders.overdue").gauge().value()).isZero();
        jdbc.sql("""
                        UPDATE ordering.order_processes SET deadline_at = now() - interval '2 minutes'
                        WHERE order_id = :id
                        """).param("id", orderId).update();
        assertThat(metrics.get("ecom.orders.overdue").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("ecom.orders.oldest.overdue.age").gauge().value()).isBetween(120.0, 150.0);

        expect(202, cancel(asha, orderId));
        deliver();

        assertThat(metrics.get("ecom.orders.current").tag("status", "AWAITING_PAYMENT").gauge().value()).isZero();
        assertThat(metrics.get("ecom.orders.current").tag("status", "CANCELLED").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("ecom.orders.overdue").gauge().value()).isZero();
        assertThat(metrics.get("ecom.orders.oldest.overdue.age").gauge().value()).isZero();
    }
}