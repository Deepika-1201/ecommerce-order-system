package com.ecommerce.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names a message payload and its schema version (event model §2). Messages with a {@code topic} are integration
 * events and are also relayed to Kafka; the others stay inside the system.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MessageType {

    String name();

    int version() default 1;

    String topic() default "";
}
