package com.ecommerce.platform.tasks;

import com.ecommerce.platform.TaskRequest;
import com.ecommerce.platform.TaskScheduler;
import com.ecommerce.shared.Ids;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The in-process adapter (ADR-003): tasks live in PostgreSQL and run on this application's workers. */
@Component
class InProcessTaskScheduler implements TaskScheduler {

    private final TaskRepository repository;
    private final TaskHandlerRegistry registry;
    private final JsonMapper json;

    InProcessTaskScheduler(TaskRepository repository, TaskHandlerRegistry registry, JsonMapper json) {
        this.repository = repository;
        this.registry = registry;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(TaskRequest request) {
        if (!registry.handles(request.type()) || registry.handlerFor(request.type()).recurring()) {
            throw new IllegalStateException("No handler runs one-off tasks of type " + request.type());
        }
        repository.insert(Ids.newId(), request, json.writeValueAsString(request.payload()));
        repository.notifyWorkers();
    }
}
