package com.ecommerce.platform;

import java.time.Instant;
import org.springframework.util.Assert;

/**
 * A task to run once. Without {@code runAt} it runs as soon as possible; with a {@code dedupeKey}, at most one such
 * task is pending or running at a time.
 */
public record TaskRequest(
        String type, Object payload, String dedupeKey, Instant runAt, int maxAttempts, String correlationId) {

    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    public TaskRequest {
        Assert.hasText(type, "type must not be empty");
        Assert.isTrue(maxAttempts >= 1, "maxAttempts must be at least 1");
    }

    public static TaskRequest of(String type, Object payload) {
        return new TaskRequest(type, payload, null, null, DEFAULT_MAX_ATTEMPTS, null);
    }

    public TaskRequest dedupeKey(String key) {
        return new TaskRequest(type, payload, key, runAt, maxAttempts, correlationId);
    }

    public TaskRequest runAt(Instant time) {
        return new TaskRequest(type, payload, dedupeKey, time, maxAttempts, correlationId);
    }

    public TaskRequest maxAttempts(int attempts) {
        return new TaskRequest(type, payload, dedupeKey, runAt, attempts, correlationId);
    }

    public TaskRequest correlatedWith(String id) {
        return new TaskRequest(type, payload, dedupeKey, runAt, maxAttempts, id);
    }
}
