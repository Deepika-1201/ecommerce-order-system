package com.ecommerce.platform.tasks;

import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.WorkerComponent;
import com.ecommerce.platform.tasks.TaskHandlerRegistry.RegisteredTask;
import com.ecommerce.platform.workers.Backoff;
import com.ecommerce.platform.workers.Wakeups;
import com.ecommerce.platform.workers.WorkerLoop;
import com.ecommerce.platform.workers.WorkerProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs due tasks (LLD §2.7): lease one, run its handler outside any transaction, then record the result if the
 * lease is still this worker's.
 */
@WorkerComponent
class TaskRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final TaskRepository repository;
    private final TaskHandlerRegistry registry;
    private final JsonMapper json;
    private final TaskProperties properties;
    private final WorkerProperties workers;
    private final WorkerLoop loop;

    TaskRunner(TaskRepository repository, TaskHandlerRegistry registry, JsonMapper json, TaskProperties properties,
            WorkerProperties workers, Wakeups wakeups) {
        this.repository = repository;
        this.registry = registry;
        this.json = json;
        this.properties = properties;
        this.workers = workers;
        this.loop = new WorkerLoop("task-runner", properties.concurrency(), wakeups.of(Wakeups.TASKS),
                properties.pollInterval(), this::runNext);
    }

    @Override
    public void start() {
        if (workers.autostart()) {
            loop.start();
        }
    }

    @Override
    public void stop() {
        loop.stop(Duration.ofSeconds(10));
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    /** Runs the next due task, if any; returns whether there was one. */
    boolean runNext() {
        String[] types = registry.types();
        if (types.length == 0) {
            return false;
        }
        ClaimedTask task = repository.claimNext(types, properties.defaultLease()).orElse(null);
        if (task == null) {
            return false;
        }
        if (task.correlationId() != null) {
            MDC.put("correlation_id", task.correlationId());
        }
        MDC.put("task_id", task.id().toString());
        try {
            run(task, registry.handlerFor(task.type()));
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("task_id");
        }
        return true;
    }

    private void run(ClaimedTask task, RegisteredTask handler) {
        Object payload;
        try {
            // Lenient even if the shared mapper is made strict for the API: a field added by a newer version must
            // not kill the task on an older worker during a rolling deploy.
            payload = handler.payloadType() == Void.class
                    ? null
                    : json.readerFor(handler.payloadType())
                            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(task.payload());
        } catch (JacksonException e) {
            recordFailure(task, e, true);
            return;
        }
        try {
            handler.invoke(new TaskExecution<>(task.id(), task.type(), task.attempt(), task.correlationId(), payload));
        } catch (RuntimeException e) {
            recordFailure(task, e, false);
            return;
        }
        boolean recorded = task.recurring() ? repository.reschedule(task) : repository.complete(task);
        if (!recorded) {
            log.warn("Task {} ({}) finished attempt {} after losing its lease; another worker owns it now",
                    task.id(), task.type(), task.attempt());
        }
    }

    private void recordFailure(ClaimedTask task, RuntimeException failure, boolean unreadable) {
        String error = NestedExceptionUtils.getMostSpecificCause(failure).toString();
        boolean recorded;
        if (task.recurring()) {
            log.warn("Recurring task {} failed; it runs again after its interval", task.type(), failure);
            recorded = repository.failRecurring(task, error);
        } else {
            boolean dead = unreadable || task.attempt() >= task.maxAttempts();
            Duration retryAfter = Backoff.delay(task.attempt(), properties.initialBackoff(), properties.maxBackoff());
            if (dead) {
                log.error("Task {} ({}) is dead after {} attempts", task.id(), task.type(), task.attempt(), failure);
            } else {
                log.warn("Task {} ({}) failed attempt {}; retrying in {}", task.id(), task.type(), task.attempt(),
                        retryAfter, failure);
            }
            recorded = repository.fail(task, error, retryAfter, dead);
        }
        if (!recorded) {
            log.warn("Task {} ({}) failed attempt {} after losing its lease; another worker owns it now",
                    task.id(), task.type(), task.attempt());
        }
    }
}
