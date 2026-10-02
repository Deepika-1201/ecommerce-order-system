package com.ecommerce.platform.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.platform.tasks.TaskFixtures.RecordTask;
import com.ecommerce.platform.tasks.TaskFixtures.RecordingTasks;
import com.ecommerce.support.Eventually;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** With polling slowed to an hour, only the LISTEN/NOTIFY wake-up can run a task within seconds (LLD §2.6). */
@Import(TaskFixtures.class)
@TestPropertySource(properties = {
    "ecom.workers.autostart=true",
    "ecom.messaging.poll-interval=1h",
    "ecom.tasks.poll-interval=1h"
})
@DirtiesContext
class BackgroundTaskRunTests extends IntegrationTest {

    @Autowired
    private TaskScheduler scheduler;

    @Autowired
    private RecordingTasks tasks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        PlatformTables.clear(jdbc);
        tasks.reset();
    }

    @Test
    void aCommittedTaskWakesTheRunnerWithoutWaitingForThePoll() {
        // The first run shows the loops and the listener are up.
        schedule("first");
        Eventually.await(Duration.ofSeconds(15), "the first run", () -> tasks.labels().contains("first"));

        long start = System.nanoTime();
        schedule("second");
        Eventually.await(Duration.ofSeconds(5), "a run after the notification",
                () -> tasks.labels().contains("second"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    private void schedule(String label) {
        transactions.executeWithoutResult(status ->
                scheduler.schedule(TaskRequest.of(TaskFixtures.RECORD, new RecordTask(label))));
    }
}
