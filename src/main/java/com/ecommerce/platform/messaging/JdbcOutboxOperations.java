package com.ecommerce.platform.messaging;

import com.ecommerce.platform.OutboxOperations;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcOutboxOperations implements OutboxOperations {

    private final OutboxRepository outbox;

    JdbcOutboxOperations(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public int redrive(UUID messageId) {
        int redriven = outbox.redrive(messageId);
        if (redriven > 0) {
            outbox.notifyWorkers();
        }
        return redriven;
    }
}
