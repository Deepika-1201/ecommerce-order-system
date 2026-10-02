package com.ecommerce.platform;

/** Schedules work that runs outside any transaction, such as calls to external systems (ADR-003, LLD §2.7). */
public interface TaskScheduler {

    /**
     * Schedules the task in the caller's transaction, which must exist; it runs only if that transaction commits.
     * The request is ignored if a pending or running task has the same dedupe key.
     *
     * @throws IllegalStateException if no handler runs the task's type
     */
    void schedule(TaskRequest request);
}
