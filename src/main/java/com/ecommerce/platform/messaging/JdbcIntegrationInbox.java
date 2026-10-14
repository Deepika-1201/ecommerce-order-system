package com.ecommerce.platform.messaging;

import com.ecommerce.platform.IntegrationInbox;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Component
class JdbcIntegrationInbox implements IntegrationInbox {

    private final OutboxRepository outbox;

    JdbcIntegrationInbox(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean receive(String consumer, UUID messageId, Runnable effect) {
        Assert.hasText(consumer, "A consumer name is required");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(effect, "effect");
        if (!outbox.markProcessed(consumer, messageId)) {
            return false;
        }
        effect.run();
        return true;
    }
}