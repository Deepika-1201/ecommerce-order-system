package com.ecommerce.platform;

/** Publishes messages through the transactional outbox (ADR-008, LLD §2.2). */
public interface Messages {

    /**
     * Writes the message to the outbox in the caller's transaction, which must exist. It is delivered after commit,
     * at least once, in {@code origin.sequence()} order per aggregate.
     *
     * @throws IllegalStateException if no handler or topic receives the payload's type
     */
    void publish(Object payload, Origin origin, Correlation correlation);
}
