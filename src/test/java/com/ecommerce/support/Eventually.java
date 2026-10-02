package com.ecommerce.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waits for something background threads do. */
public final class Eventually {

    private Eventually() {
    }

    public static void await(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
