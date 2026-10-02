package com.ecommerce.platform;

import java.util.UUID;
import org.springframework.util.Assert;

/**
 * Links a message to the flow it belongs to (usually the order id) and to the message that caused it
 * (event model §2).
 */
public record Correlation(String correlationId, UUID causationId) {

    public Correlation {
        Assert.hasText(correlationId, "correlationId must not be empty");
    }

    /** Starts a flow, for a message caused by an API request or a task rather than another message. */
    public static Correlation start(String correlationId) {
        return new Correlation(correlationId, null);
    }

    public static Correlation causedBy(IncomingMessage<?> cause) {
        return new Correlation(cause.correlationId(), cause.messageId());
    }
}
