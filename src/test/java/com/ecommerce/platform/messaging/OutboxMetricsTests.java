package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.messaging.MessagingFixtures.Publisher;
import com.ecommerce.platform.messaging.MessagingFixtures.SomethingPublic;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(MessagingFixtures.class)
class OutboxMetricsTests extends IntegrationTest {

    @Autowired
    private Publisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void clear() {
        PlatformTables.clear(jdbc);
    }

    @Test
    void emptyQueuesExposeZeroAndOnlyFixedChannelLabels() {
        for (String channel : new String[] {"internal", "kafka"}) {
            assertThat(value("pending", channel)).isZero();
            assertThat(value("parked", channel)).isZero();
            assertThat(value("oldest.age", channel)).isZero();
        }
        assertThat(metrics.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("ecom.outbox.")))
                .hasSize(6).allSatisfy(meter ->
                        assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("channel"));
    }

    @Test
    void parkedRowsRemainInTheBacklogAndDeliveredRowsDisappear() {
        publisher.publish(new SomethingPublic("first"), "metrics-order", 1);
        publisher.publish(new SomethingPublic("second"), "metrics-order", 2);
        publisher.step("internal-order", 1);
        jdbc.sql("UPDATE platform.outbox SET created_at = now() - interval '3 minutes'").update();
        jdbc.sql("UPDATE platform.outbox SET parked_at = now() WHERE destination LIKE 'kafka:%' AND sequence = 1")
                .update();

        assertThat(value("pending", "kafka")).isEqualTo(2);
        assertThat(value("parked", "kafka")).isEqualTo(1);
        assertThat(value("oldest.age", "kafka")).isBetween(180.0, 195.0);
        assertThat(value("pending", "internal")).isEqualTo(2);

        jdbc.sql("UPDATE platform.outbox SET delivered_at = now() WHERE destination LIKE 'kafka:%'").update();

        assertThat(value("pending", "kafka")).isZero();
        assertThat(value("parked", "kafka")).isZero();
        assertThat(value("oldest.age", "kafka")).isZero();
        assertThat(value("pending", "internal")).isEqualTo(2);
    }

    @Test
    void prometheusIsAvailableOnlyOnTheManagementPort() {
        publisher.publish(new SomethingPublic("private-payload"), "private-order-id", 1);

        var scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("ecom_outbox_pending", "ecom_outbox_oldest_age_seconds",
                "channel=\"kafka\"").doesNotContain("private-order-id", "private-payload");
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }

    private double value(String suffix, String channel) {
        return metrics.get("ecom.outbox." + suffix).tag("channel", channel).gauge().value();
    }
}