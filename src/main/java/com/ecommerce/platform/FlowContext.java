package com.ecommerce.platform;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Scopes opaque order/work identifiers without leaking a previous task's MDC into the next one. */
public final class FlowContext implements AutoCloseable {

    private final Map<String, String> previous;

    private FlowContext(String correlationId, UUID messageId, UUID taskId) {
        previous = MDC.getCopyOfContextMap();
        put("correlation_id", canonicalId(correlationId));
        put("message_id", messageId == null ? null : messageId.toString());
        put("task_id", taskId == null ? null : taskId.toString());
    }

    public static FlowContext message(String correlationId, UUID messageId) {
        return new FlowContext(correlationId, messageId, null);
    }

    public static FlowContext task(String correlationId, UUID taskId) {
        return new FlowContext(correlationId, null, taskId);
    }

    @Override
    public void close() {
        MDC.setContextMap(previous == null ? Map.of() : previous);
    }

    private static String canonicalId(String candidate) {
        if (candidate == null || candidate.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(candidate).toString().equals(candidate) ? candidate : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}