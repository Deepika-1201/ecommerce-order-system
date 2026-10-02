package com.ecommerce.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method {@code void name(TaskExecution<T> task)} that runs tasks of a type (LLD §2.7). It runs outside any
 * transaction and at least once per task, so it must be idempotent; {@code task.taskId()} is a stable idempotency
 * key for external calls. A failure is retried with backoff.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HandlesTask {

    String type();

    /** Makes the task recurring at this fixed rate, such as {@code "1h"}; recurring tasks take no payload. */
    String every() default "";
}
