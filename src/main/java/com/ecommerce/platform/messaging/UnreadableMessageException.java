package com.ecommerce.platform.messaging;

import java.util.UUID;

/** A message that can never be read, so retrying it is pointless: it is parked at once. */
class UnreadableMessageException extends RuntimeException {

    UnreadableMessageException(UUID messageId, Throwable cause) {
        super("Message " + messageId + " cannot be read: " + cause.getMessage(), cause);
    }
}
