package com.ecommerce.platform.workers;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wakes the threads waiting for work of one kind. A waiter reads {@link #generation()} before looking for work and
 * passes it to {@link #awaitChange}, so a signal that arrives in between is not missed.
 */
public final class Wakeup {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long generation;

    public long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    public void signal() {
        lock.lock();
        try {
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Waits until a signal after {@code seen}, or until the timeout. */
    public void awaitChange(long seen, Duration timeout) throws InterruptedException {
        lock.lock();
        try {
            long remaining = timeout.toNanos();
            while (generation == seen && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }
}
