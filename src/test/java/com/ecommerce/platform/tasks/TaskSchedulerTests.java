package com.ecommerce.platform.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.platform.tasks.TaskFixtures.RecordTask;
import com.ecommerce.platform.tasks.TaskFixtures.RecordingTasks;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The in-process task scheduler, including crashes between claiming, running and recording (LLD §2.7). The shared
 * mapper is strict here, so the forward-compatibility test checks the runner's own leniency.
 */
@Import(TaskFixtures.class)
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
class TaskSchedulerTests extends IntegrationTest {

    private static final String[] RECORD_TYPE = {TaskFixtures.RECORD};

    @Autowired
    private TaskScheduler scheduler;

    @Autowired
    private TaskRunner runner;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private RecurringTaskRegistrar registrar;

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
    void schedulingNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> scheduler.schedule(record("x")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionSchedulesNothing() {
        transactions.executeWithoutResult(status -> {
            scheduler.schedule(record("x"));
            status.setRollbackOnly();
        });

        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks")).isZero();
    }

    @Test
    void anUnknownTaskTypeCannotBeScheduled() {
        assertThatThrownBy(() -> schedule(TaskRequest.of("test.nobody", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.nobody");
    }

    @Test
    void aCommittedTaskRunsOnceAndSucceeds() {
        schedule(record("x"));

        runAllDue();
        runAllDue();

        assertThat(tasks.labels()).containsExactly("x");
        assertThat(state("x")).isEqualTo(new TaskState("SUCCEEDED", 1, null));
    }

    @Test
    void aFailedTaskIsRetriedAfterABackoff() {
        tasks.failNext("x", 1);
        schedule(record("x"));

        runAllDue();
        assertThat(tasks.labels()).isEmpty();
        assertThat(state("x").status()).isEqualTo("PENDING");
        assertThat(state("x").lastError()).contains("Injected task failure");
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE run_at > now() + interval '5 seconds'"))
                .as("waits for its backoff")
                .isEqualTo(1);

        retryNow();
        runAllDue();
        assertThat(tasks.attempts("x")).containsExactly(2);
        assertThat(state("x").status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aTaskThatKeepsFailingIsDeadAfterItsLastAttempt() {
        tasks.failNext("x", 10);
        schedule(record("x").maxAttempts(3));

        for (int attempt = 1; attempt <= 3; attempt++) {
            runAllDue();
            retryNow();
        }
        runAllDue();

        assertThat(state("x").status()).isEqualTo("DEAD");
        assertThat(state("x").attempts()).isEqualTo(3);
        assertThat(tasks.labels()).isEmpty();
    }

    @Test
    void aTaskWithFieldsFromANewerVersionStillRuns() {
        jdbc.sql("""
                INSERT INTO platform.scheduled_tasks (id, type, dedupe_key, payload, status, run_at, attempts,
                                                      max_attempts, created_at, updated_at)
                VALUES (?, ?, 'x', '{"label": "x", "added_later": 1}', 'PENDING', now(), 0, 10, now(), now())
                """).params(UUID.randomUUID(), TaskFixtures.RECORD).update();

        runAllDue();

        assertThat(tasks.labels()).containsExactly("x");
    }

    @Test
    void aTaskWithAnUnreadablePayloadIsDeadAtOnce() {
        jdbc.sql("""
                INSERT INTO platform.scheduled_tasks (id, type, dedupe_key, payload, status, run_at, attempts,
                                                      max_attempts, created_at, updated_at)
                VALUES (?, ?, 'x', '{"label":', 'PENDING', now(), 0, 10, now(), now())
                """).params(UUID.randomUUID(), TaskFixtures.RECORD).update();

        runAllDue();

        assertThat(state("x").status()).isEqualTo("DEAD");
        assertThat(state("x").attempts()).isEqualTo(1);
    }

    @Test
    void aTaskWithTheDedupeKeyOfAnActiveTaskIsIgnored() {
        schedule(record("x"));
        schedule(record("x"));
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks")).isEqualTo(1);

        runAllDue();
        schedule(record("x"));

        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE status = 'PENDING'"))
                .as("a finished task no longer blocks its key")
                .isEqualTo(1);
    }

    @Test
    void aTaskScheduledForLaterWaitsUntilItIsDue() {
        schedule(record("x").runAt(Instant.now().plus(Duration.ofHours(1))));

        assertThat(runner.runNext()).isFalse();

        retryNow();
        assertThat(runner.runNext()).isTrue();
        assertThat(tasks.labels()).containsExactly("x");
    }

    @Test
    void aTaskWhoseWorkerDiedIsTakenOverWhenItsLeaseExpires() {
        schedule(record("x"));
        assertThat(repository.claimNext(RECORD_TYPE, Duration.ofHours(1))).isPresent();
        assertThat(runner.runNext()).as("leased to the worker that died").isFalse();

        expireLeases();
        runAllDue();

        assertThat(tasks.attempts("x")).containsExactly(2);
        assertThat(state("x")).isEqualTo(new TaskState("SUCCEEDED", 2, null));
    }

    @Test
    void aWorkerThatLostItsLeaseCannotOverwriteTheResult() {
        schedule(record("x"));
        ClaimedTask stale = repository.claimNext(RECORD_TYPE, Duration.ofHours(1)).orElseThrow();
        expireLeases();
        runAllDue();

        assertThat(repository.complete(stale)).isFalse();
        assertThat(repository.fail(stale, "late failure", Duration.ofSeconds(1), true)).isFalse();
        assertThat(state("x")).isEqualTo(new TaskState("SUCCEEDED", 2, null));
    }

    @Test
    void twoRunnersRunEachTaskOnce() throws Exception {
        List<String> labels = IntStream.range(0, 40).mapToObj(index -> "t" + index).toList();
        labels.forEach(label -> schedule(record(label)));

        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> running = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                running.add(workers.submit(() -> {
                    while (runner.runNext()) {
                        // until nothing is due
                    }
                }));
            }
            for (Future<?> worker : running) {
                worker.get();
            }
        }

        assertThat(tasks.labels()).hasSize(40).containsExactlyInAnyOrderElementsOf(labels);
    }

    @Test
    void aRecurringTaskRunsAtAFixedRate() {
        registrar.afterSingletonsInstantiated();
        Instant due = runAt(TaskFixtures.TICK);

        runAllDue();

        assertThat(tasks.ticks()).isEqualTo(1);
        assertThat(runAt(TaskFixtures.TICK)).isEqualTo(due.plus(Duration.ofHours(1)));
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE status <> 'PENDING'")).isZero();
    }

    @Test
    void recurringRunsMissedWhileDownCollapseIntoOne() {
        registrar.afterSingletonsInstantiated();
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() - interval '5 hours 1 minute'").update();

        runAllDue();

        assertThat(tasks.ticks()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE type = '" + TaskFixtures.TICK + "' "
                + "AND run_at > now() AND run_at <= now() + interval '1 hour'")).isEqualTo(1);
    }

    @Test
    void aFailedRecurringRunWaitsForTheNextInterval() {
        registrar.afterSingletonsInstantiated();
        tasks.failNextTicks(1);

        runAllDue();

        assertThat(tasks.ticks()).isZero();
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks "
                + "WHERE status = 'PENDING' AND run_at > now() + interval '59 minutes' AND last_error IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    void registeringRecurringTasksAgainKeepsOneRowEach() {
        registrar.afterSingletonsInstantiated();
        registrar.afterSingletonsInstantiated();

        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE type = '" + TaskFixtures.TICK + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.scheduled_tasks WHERE type = 'platform.cleanup'"))
                .isEqualTo(1);
    }

    private static TaskRequest record(String label) {
        return TaskRequest.of(TaskFixtures.RECORD, new RecordTask(label)).dedupeKey(label);
    }

    private void schedule(TaskRequest request) {
        transactions.executeWithoutResult(status -> scheduler.schedule(request));
    }

    private void runAllDue() {
        while (runner.runNext()) {
            // until nothing is due
        }
    }

    private void retryNow() {
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE status = 'PENDING'").update();
    }

    private void expireLeases() {
        jdbc.sql("UPDATE platform.scheduled_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE status = 'RUNNING'").update();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private Instant runAt(String type) {
        return jdbc.sql("SELECT run_at FROM platform.scheduled_tasks WHERE type = ?")
                .param(type)
                .query((row, number) -> row.getObject("run_at", OffsetDateTime.class).toInstant())
                .single();
    }

    /** The task with the dedupe key; tasks in these tests use their label as dedupe key. */
    private TaskState state(String dedupeKey) {
        return jdbc.sql("SELECT status, attempts, last_error FROM platform.scheduled_tasks WHERE dedupe_key = ? "
                        + "ORDER BY created_at DESC LIMIT 1")
                .param(dedupeKey)
                .query(TaskState.class)
                .single();
    }

    record TaskState(String status, int attempts, String lastError) {
    }
}
