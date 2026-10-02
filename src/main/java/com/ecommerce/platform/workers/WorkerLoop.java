package com.ecommerce.platform.workers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a unit of work repeatedly on virtual threads. A thread that finds nothing to do waits for a wake-up or the
 * poll interval, whichever comes first (LLD §2.6); after failures it waits longer, up to 30 seconds.
 */
public final class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final Duration MAX_FAILURE_WAIT = Duration.ofSeconds(30);

    private final String name;
    private final int threads;
    private final Wakeup wakeup;
    private final Duration pollInterval;
    private final BooleanSupplier work;
    private final List<Thread> running = new ArrayList<>();
    private volatile boolean stopping;

    /** {@code work} returns whether it found something to do; if so, it runs again at once. */
    public WorkerLoop(String name, int threads, Wakeup wakeup, Duration pollInterval, BooleanSupplier work) {
        this.name = name;
        this.threads = threads;
        this.wakeup = wakeup;
        this.pollInterval = pollInterval;
        this.work = work;
    }

    public synchronized void start() {
        if (!running.isEmpty()) {
            return;
        }
        stopping = false;
        for (int index = 0; index < threads; index++) {
            running.add(Thread.ofVirtual().name(name + "-" + index).start(this::run));
        }
        log.info("Started {} with {} threads", name, threads);
    }

    /** Lets each thread finish its current unit of work, then interrupts any still running at the timeout. */
    public synchronized void stop(Duration timeout) {
        stopping = true;
        wakeup.signal();
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : running) {
            try {
                if (!thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                    thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                thread.interrupt();
            }
        }
        running.clear();
    }

    public synchronized boolean isRunning() {
        return !running.isEmpty();
    }

    private void run() {
        int failures = 0;
        while (!stopping) {
            long seen = wakeup.generation();
            boolean worked = false;
            try {
                worked = work.getAsBoolean();
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                log.error("{} failed ({} in a row)", name, failures, e);
            }
            if (!worked && !stopping) {
                Duration wait = failures == 0 ? pollInterval : Backoff.delay(failures, pollInterval, MAX_FAILURE_WAIT);
                try {
                    wakeup.awaitChange(seen, wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
