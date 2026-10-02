package com.ecommerce.platform;

import java.util.UUID;

/** Operator actions on the outbox (LLD §2.4). */
public interface OutboxOperations {

    /** Returns the message's parked deliveries to the queue with their attempts reset; returns how many there were. */
    int redrive(UUID messageId);
}
