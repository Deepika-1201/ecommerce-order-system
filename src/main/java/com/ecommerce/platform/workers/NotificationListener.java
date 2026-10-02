package com.ecommerce.platform.workers;

import com.ecommerce.platform.WorkerComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Holds one connection that listens on the notification channels and wakes the matching worker loops (LLD §2.6).
 * Polling covers any gap, so a lost connection only delays work until it reconnects.
 */
@WorkerComponent
class NotificationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private static final int RECEIVE_TIMEOUT_MILLIS = 500;
    private static final Duration MIN_RECONNECT_WAIT = Duration.ofMillis(500);
    private static final Duration MAX_RECONNECT_WAIT = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final Wakeups wakeups;
    private final WorkerProperties properties;
    private volatile boolean running;
    private Thread thread;

    NotificationListener(DataSource dataSource, Wakeups wakeups, WorkerProperties properties) {
        this.dataSource = dataSource;
        this.wakeups = wakeups;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (!properties.autostart() || running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("notification-listener").start(this::listen);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        Duration reconnectWait = MIN_RECONNECT_WAIT;
        while (running) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String channel : wakeups.channels()) {
                    statement.execute("LISTEN " + channel);
                }
                PGConnection notifications = connection.unwrap(PGConnection.class);
                reconnectWait = MIN_RECONNECT_WAIT;
                // A missed notification is harmless, but waking everyone once after (re)connecting avoids waiting for the next poll.
                wakeups.channels().forEach(channel -> wakeups.of(channel).signal());
                while (running) {
                    PGNotification[] received = notifications.getNotifications(RECEIVE_TIMEOUT_MILLIS);
                    if (received != null) {
                        for (PGNotification notification : received) {
                            wakeups.of(notification.getName()).signal();
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Notification listener lost its connection; reconnecting in {} (polling continues)", reconnectWait, e);
                try {
                    Thread.sleep(reconnectWait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                reconnectWait = reconnectWait.multipliedBy(2).compareTo(MAX_RECONNECT_WAIT) > 0
                        ? MAX_RECONNECT_WAIT
                        : reconnectWait.multipliedBy(2);
            }
        }
    }
}
