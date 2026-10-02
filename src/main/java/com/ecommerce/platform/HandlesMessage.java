package com.ecommerce.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method {@code void name(IncomingMessage<T> message)} that handles messages of type {@code T} (LLD §2.4).
 * It runs in the delivery transaction: it may change state, publish messages and schedule tasks, but never calls
 * external systems.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HandlesMessage {

    /** A unique, stable name: it addresses this handler's outbox rows and processed-message records. */
    String consumer();
}
