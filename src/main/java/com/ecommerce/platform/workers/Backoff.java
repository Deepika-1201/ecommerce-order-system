package com.ecommerce.platform.workers;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter, so retries from many workers spread out. */
public final class Backoff {

    private Backoff() {
    }

    /** The delay before retry number {@code attempt} (from 1): between half and all of {@code initial * 2^(attempt-1)}, capped. */
    public static Duration delay(int attempt, Duration initial, Duration max) {
        double exponential = initial.toMillis() * Math.pow(2, Math.max(0, attempt - 1));
        long capped = (long) Math.min(exponential, max.toMillis());
        long jittered = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        return Duration.ofMillis(jittered);
    }
}
