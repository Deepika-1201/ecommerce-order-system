package com.ecommerce.platform;

/**
 * Verified webhooks from external systems, stored before they are acknowledged and then processed by a task
 * (LLD §7.7). Bodies are kept byte for byte, so a signed body stays verifiable.
 */
public interface WebhookInbox {

    /**
     * Stores the webhook in the caller's transaction, which must exist, unless one with the same source and event id
     * is stored already. A new one schedules {@code taskType}, with the webhook as its payload.
     *
     * @return whether the webhook was new
     */
    boolean store(ReceivedWebhook webhook, String body, String taskType);

    /** The body as it was received; a webhook that is not stored is a bug. */
    String body(ReceivedWebhook webhook);

    /** Marks the webhook processed, in the caller's transaction, which must exist. */
    void markProcessed(ReceivedWebhook webhook);
}
