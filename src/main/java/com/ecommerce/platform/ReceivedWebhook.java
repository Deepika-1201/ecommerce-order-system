package com.ecommerce.platform;

import org.springframework.util.Assert;

/** A webhook in the {@link WebhookInbox}: who sent it, its id there, and its type. */
public record ReceivedWebhook(String source, String eventId, String type) {

    public ReceivedWebhook {
        Assert.hasText(source, "source must not be empty");
        Assert.hasText(eventId, "eventId must not be empty");
        Assert.hasText(type, "type must not be empty");
    }
}
