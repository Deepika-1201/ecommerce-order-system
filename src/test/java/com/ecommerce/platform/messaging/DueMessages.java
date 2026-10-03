package com.ecommerce.platform.messaging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Delivers outbox messages on the test thread, for tests outside this package whose worker loops are stopped. A
 * delivery that fails is an assertion error, since its retry would wait for the backoff.
 */
public final class DueMessages {

    private static final int RUNAWAY = 10_000;

    private DueMessages() {
    }

    /** Delivers due messages until none is left, including those that handlers publish; returns how many. */
    public static int deliverAll(ApplicationContext context) {
        return deliver(context, Set.of());
    }

    /** As {@link #deliverAll}, leaving every message of these payload types in the outbox. */
    public static int deliverAllExcept(ApplicationContext context, Class<?>... heldBack) {
        MessageHandlerRegistry handlers = context.getBean(MessageHandlerRegistry.class);
        Set<String> held = new HashSet<>();
        for (Class<?> type : heldBack) {
            held.addAll(handlers.destinationsFor(MessageTypes.of(type)));
        }
        return deliver(context, held);
    }

    private static int deliver(ApplicationContext context, Set<String> heldBack) {
        Dispatcher dispatcher = context.getBean(Dispatcher.class);
        String[] destinations = Arrays.stream(context.getBean(MessageHandlerRegistry.class).handlerDestinations())
                .filter(destination -> !heldBack.contains(destination))
                .toArray(String[]::new);
        int delivered = 0;
        while (dispatcher.deliverNext(destinations)) {
            if (++delivered > RUNAWAY) {
                throw new AssertionError("Handlers kept publishing messages: more than " + RUNAWAY + " deliveries");
            }
        }
        List<String> failed = context.getBean(JdbcClient.class)
                .sql("""
                        SELECT type || ' to ' || destination || ': ' || last_error FROM platform.outbox
                        WHERE delivered_at IS NULL AND last_error IS NOT NULL ORDER BY id
                        """)
                .query(String.class)
                .list();
        if (!failed.isEmpty()) {
            throw new AssertionError("Failed deliveries:\n" + String.join("\n", failed));
        }
        return delivered;
    }
}
