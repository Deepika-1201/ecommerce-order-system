package com.ecommerce.platform.tasks;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs scheduled tasks on the test thread, for tests outside this package whose worker loops are stopped. */
public final class DueTasks {

    private DueTasks() {
    }

    /** Runs due tasks until none is left, including tasks that running ones schedule; returns how many ran. */
    public static int runAll(ApplicationContext context) {
        TaskRunner runner = context.getBean(TaskRunner.class);
        int ran = 0;
        while (runner.runNext()) {
            ran++;
        }
        return ran;
    }

    /** Makes one recurring task due now (and no other), then runs everything due, as {@link #runAll} does. */
    public static int runRecurring(ApplicationContext context, String type) {
        context.getBean(RecurringTaskRegistrar.class).afterSingletonsInstantiated();
        context.getBean(JdbcClient.class).sql("""
                        UPDATE platform.scheduled_tasks
                        SET run_at = CASE WHEN type = :type THEN now() ELSE now() + interval '1 day' END
                        WHERE every_seconds IS NOT NULL AND status = 'PENDING'
                        """)
                .param("type", type)
                .update();
        return runAll(context);
    }
}
