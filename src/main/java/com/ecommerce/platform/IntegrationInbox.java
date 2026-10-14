package com.ecommerce.platform;

import java.util.UUID;

/** Commits a received event's deduplication marker and database effect together, before a broker acknowledgement. */
public interface IntegrationInbox {

    /** Runs the database-only effect once per consumer and message ID; returns false for an already committed event. */
    boolean receive(String consumer, UUID messageId, Runnable effect);
}