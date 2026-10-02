package com.ecommerce.platform;

import java.util.UUID;

/** One attempt to run a task; {@code attempt} starts at 1. */
public record TaskExecution<T>(UUID taskId, String type, int attempt, String correlationId, T payload) {
}
