package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.messaging.MessagingFixtures.Publisher;
import com.ecommerce.platform.messaging.MessagingFixtures.SomethingPublic;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** While Kafka is down, integration events wait in the outbox (NFR-8) and are never parked. */
@Import(MessagingFixtures.class)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:1",
    "spring.kafka.producer.properties.max.block.ms=1000",
    "ecom.messaging.relay-send-timeout=2s",
    "ecom.messaging.max-attempts=3"
})
@DirtiesContext
class KafkaUnavailableTests extends IntegrationTest {

    @Autowired
    private KafkaRelay relay;

    @Autowired
    private Publisher publisher;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyOutbox() {
        PlatformTables.clear(jdbc);
    }

    @Test
    void eventsWaitInTheOutboxWithBackoffAndAreNeverParked() {
        publisher.publish(new SomethingPublic("buffered"), "order-1", 1);

        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(row()).satisfies(row -> {
            assertThat(row.delivered()).isFalse();
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.retryScheduled()).isTrue();
            assertThat(row.lastError()).isNotBlank();
        });
        assertThat(relay.relayBatch()).as("nothing is due during the backoff").isZero();

        // Far past the attempt limit that parks handler deliveries.
        for (int attempt = 2; attempt <= 4; attempt++) {
            jdbc.sql("UPDATE platform.outbox SET next_attempt_at = now()").update();
            assertThat(relay.relayBatch()).isEqualTo(1);
        }
        assertThat(row().parked()).isFalse();
        assertThat(row().attempts()).isEqualTo(4);
    }

    private RelayRow row() {
        return jdbc.sql("""
                        SELECT attempts, last_error, next_attempt_at > now() AS retry_scheduled,
                               parked_at IS NOT NULL AS parked, delivered_at IS NOT NULL AS delivered
                        FROM platform.outbox
                        """)
                .query(RelayRow.class)
                .single();
    }

    record RelayRow(int attempts, String lastError, boolean retryScheduled, boolean parked, boolean delivered) {
    }
}
