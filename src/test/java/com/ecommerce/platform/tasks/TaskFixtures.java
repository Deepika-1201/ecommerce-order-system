package com.ecommerce.platform.tasks;

import com.ecommerce.platform.HandlesTask;
import com.ecommerce.platform.TaskExecution;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Task handlers that record each run in a table, with failures on demand. */
@TestConfiguration(proxyBeanMethods = false)
class TaskFixtures {

    static final String RECORD = "test.record";
    static final String TICK = "test.tick";

    record RecordTask(String label) {
    }

    @Bean
    RecordingTasks recordingTasks(JdbcClient jdbc) {
        return new RecordingTasks(jdbc);
    }

    static class RecordingTasks {

        private final JdbcClient jdbc;
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        private final AtomicInteger tickFailures = new AtomicInteger();

        RecordingTasks(JdbcClient jdbc) {
            this.jdbc = jdbc;
            jdbc.sql("""
                    CREATE TABLE IF NOT EXISTS public.test_task_runs (
                        id bigserial PRIMARY KEY,
                        task_id uuid NOT NULL,
                        type text NOT NULL,
                        attempt integer NOT NULL,
                        label text)
                    """).update();
        }

        void reset() {
            failures.clear();
            tickFailures.set(0);
            jdbc.sql("TRUNCATE public.test_task_runs").update();
        }

        void failNext(String label, int times) {
            failures.put(label, new AtomicInteger(times));
        }

        void failNextTicks(int times) {
            tickFailures.set(times);
        }

        @HandlesTask(type = RECORD)
        void record(TaskExecution<RecordTask> task) {
            AtomicInteger remaining = failures.get(task.payload().label());
            if (remaining != null && remaining.getAndDecrement() > 0) {
                throw new IllegalStateException("Injected task failure");
            }
            write(task.taskId(), RECORD, task.attempt(), task.payload().label());
        }

        @HandlesTask(type = TICK, every = "1h")
        void tick(TaskExecution<Void> task) {
            if (tickFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("Injected tick failure");
            }
            write(task.taskId(), TICK, task.attempt(), null);
        }

        List<String> labels() {
            return jdbc.sql("SELECT label FROM public.test_task_runs WHERE type = ? ORDER BY id")
                    .param(RECORD)
                    .query(String.class)
                    .list();
        }

        List<Integer> attempts(String label) {
            return jdbc.sql("SELECT attempt FROM public.test_task_runs WHERE label = ? ORDER BY id")
                    .param(label)
                    .query(Integer.class)
                    .list();
        }

        int ticks() {
            return jdbc.sql("SELECT count(*) FROM public.test_task_runs WHERE type = ?")
                    .param(TICK)
                    .query(Integer.class)
                    .single();
        }

        private void write(UUID taskId, String type, int attempt, String label) {
            jdbc.sql("INSERT INTO public.test_task_runs (task_id, type, attempt, label) VALUES (?, ?, ?, ?)")
                    .params(taskId, type, attempt, label)
                    .update();
        }
    }
}
