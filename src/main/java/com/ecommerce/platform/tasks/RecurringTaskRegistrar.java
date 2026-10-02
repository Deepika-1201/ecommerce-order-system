package com.ecommerce.platform.tasks;

import com.ecommerce.shared.Ids;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Ensures each recurring task has its row once the schema is migrated; safe to run on every instance. */
@Component
class RecurringTaskRegistrar implements SmartInitializingSingleton {

    private final TaskHandlerRegistry registry;
    private final TaskRepository repository;

    RecurringTaskRegistrar(TaskHandlerRegistry registry, TaskRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.recurring().forEach(task -> repository.registerRecurring(Ids.newId(), task.type(), task.every()));
    }
}
