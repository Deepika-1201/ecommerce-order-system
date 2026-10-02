package com.ecommerce.platform.tasks;

import java.util.UUID;

/** A task a worker has leased; {@code attempt} fences its completion against a later lease. */
record ClaimedTask(
        UUID id, String type, String payload, int attempt, int maxAttempts, Long everySeconds, String correlationId) {

    boolean recurring() {
        return everySeconds != null;
    }
}
