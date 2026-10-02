package com.ecommerce.platform.messaging;

import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.MessageType;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import com.ecommerce.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcMessages implements Messages {

    private final OutboxRepository outbox;
    private final MessageHandlerRegistry handlers;
    private final JsonMapper json;
    private final Clock clock;

    JdbcMessages(OutboxRepository outbox, MessageHandlerRegistry handlers, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.handlers = handlers;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Object payload, Origin origin, Correlation correlation) {
        MessageType type = MessageTypes.of(payload.getClass());
        List<String> destinations = handlers.destinationsFor(type);
        if (destinations.isEmpty()) {
            throw new IllegalStateException(
                    "No handler or topic receives message type " + type.name() + " version " + type.version());
        }
        Envelope envelope = new Envelope(
                Ids.newId(),
                type.name(),
                type.version(),
                Instant.now(clock),
                MessageTypes.source(payload.getClass()),
                origin.aggregateType(),
                origin.aggregateId(),
                origin.sequence(),
                correlation.correlationId(),
                correlation.causationId(),
                null,
                json.valueToTree(payload));
        String serialized = json.writeValueAsString(envelope);
        destinations.forEach(destination -> outbox.insert(destination, envelope, serialized));
        outbox.notifyWorkers();
    }
}
