package com.ecommerce.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.messaging.MessagingFixtures.Publisher;
import com.ecommerce.platform.messaging.MessagingFixtures.RecordingHandlers;
import com.ecommerce.support.Eventually;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** With polling slowed to an hour, only the LISTEN/NOTIFY wake-up can deliver within seconds (LLD §2.6). */
@Import(MessagingFixtures.class)
@TestPropertySource(properties = {
    "ecom.workers.autostart=true",
    "ecom.messaging.poll-interval=1h",
    "ecom.tasks.poll-interval=1h"
})
@DirtiesContext
class BackgroundDeliveryTests extends IntegrationTest {

    @Autowired
    private Publisher publisher;

    @Autowired
    private RecordingHandlers handlers;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.clear(jdbc);
        handlers.reset();
    }

    @Test
    void aCommittedMessageWakesTheDispatcherWithoutWaitingForThePoll() {
        // The first delivery shows the loops and the listener are up.
        String first = "first-" + UUID.randomUUID();
        publisher.step(first, 1);
        Eventually.await(Duration.ofSeconds(15), "the first delivery",
                () -> handlers.steps(MessagingFixtures.RECORDER, first).equals(List.of(1)));

        String second = "second-" + UUID.randomUUID();
        long start = System.nanoTime();
        publisher.step(second, 1);
        Eventually.await(Duration.ofSeconds(5), "delivery after the notification",
                () -> handlers.steps(MessagingFixtures.RECORDER, second).equals(List.of(1)));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
