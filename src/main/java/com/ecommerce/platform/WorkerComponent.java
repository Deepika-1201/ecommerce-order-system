package com.ecommerce.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/** A component that exists only in instances running the worker role, such as the outbox dispatcher (ADR-004). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@ConditionalOnRole(Role.WORKER)
public @interface WorkerComponent {
}
