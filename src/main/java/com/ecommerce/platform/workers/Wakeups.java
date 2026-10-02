package com.ecommerce.platform.workers;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** One {@link Wakeup} per PostgreSQL notification channel (LLD §2.6). */
@Component
public class Wakeups {

    public static final String OUTBOX = "ecom_outbox";
    public static final String TASKS = "ecom_tasks";

    private final Map<String, Wakeup> channels = Map.of(OUTBOX, new Wakeup(), TASKS, new Wakeup());

    public Wakeup of(String channel) {
        Wakeup wakeup = channels.get(channel);
        if (wakeup == null) {
            throw new IllegalArgumentException("Unknown notification channel " + channel);
        }
        return wakeup;
    }

    public Set<String> channels() {
        return channels.keySet();
    }
}
