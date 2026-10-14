package com.ecommerce.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecommerce.notifications.EmailDelivery;
import com.ecommerce.platform.IntegrationEvent;
import com.ecommerce.platform.TaskExecution;
import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.support.IntegrationTest;
import com.ecommerce.support.PlatformTables;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

class NotificationTests extends IntegrationTest {

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private EmailDelivery email;

    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE notifications.notifications, notifications.order_progress").update();
        PlatformTables.clear(jdbc);
        jdbc.sql("""
                        INSERT INTO customer.customers (id, subject, email, created_at, updated_at)
                        VALUES (:id, :subject, 'asha@example.test', now(), now())
                        """)
                .param("id", customerId).param("subject", "notifications-" + customerId).update();
    }

    @AfterEach
    void removeFailure() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_notification_task ON platform.scheduled_tasks").update();
    }

    @Test
    void duplicatesQueueOneNotificationAndOneTaskBeforeSending() {
        IntegrationEvent event = event("order.placed", 1);
        notifications.accept(event);
        notifications.accept(event);
        notifications.accept(event("order.placed", 2));

        assertThat(count("notifications.notifications")).isEqualTo(1);
        assertThat(count("platform.scheduled_tasks")).isEqualTo(1);
        verifyNoInteractions(email);

        DueTasks.runAll(context);
        assertThat(status()).isEqualTo("SENT");
        verify(email).send(any(), eq("asha@example.test"), eq("Order placed"), anyString());
        DueTasks.runAll(context);
        verify(email, times(1)).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void olderMilestonesDoNotQueueMisleadingLateNotifications() {
        notifications.accept(event("order.confirmed", 4));
        notifications.accept(event("order.placed", 1));

        assertThat(count("notifications.notifications")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT kind FROM notifications.notifications").query(String.class).single())
                .isEqualTo("order.confirmed");
        assertThat(jdbc.sql("SELECT last_sequence FROM notifications.order_progress").query(Long.class).single())
                .isEqualTo(4);
    }

    @Test
    void queueProgressAndInboxRollBackIfSchedulingFails() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION public.test_fail_notification_task() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated task write failure';
                END
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_notification_task BEFORE INSERT ON platform.scheduled_tasks
                FOR EACH ROW EXECUTE FUNCTION public.test_fail_notification_task()
                """).update();
        IntegrationEvent event = event("order.placed", 1);

        assertThatThrownBy(() -> notifications.accept(event)).isInstanceOf(RuntimeException.class);
        assertThat(count("notifications.notifications")).isZero();
        assertThat(count("notifications.order_progress")).isZero();
        assertThat(count("platform.processed_messages")).isZero();
        assertThat(count("platform.scheduled_tasks")).isZero();

        jdbc.sql("DROP TRIGGER test_fail_notification_task ON platform.scheduled_tasks").update();
        notifications.accept(event);
        assertThat(count("notifications.notifications")).isEqualTo(1);
    }

    @Test
    void aFailedSendRemainsRetryableWithoutBlockingConsumption() {
        notifications.accept(event("order.placed", 1));
        doThrow(new EmailDelivery.Unavailable()).when(email).send(any(), anyString(), anyString(), anyString());

        DueTasks.runAll(context);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT last_error FROM notifications.notifications").query(String.class).single())
                .isEqualTo("delivery_failed");
        assertThat(count("platform.processed_messages")).isEqualTo(1);

        reset(email);
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE type = 'notifications.send-email'").update();
        DueTasks.runAll(context);
        assertThat(status()).isEqualTo("SENT");
        verify(email).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void theLastFailedAttemptIsVisibleRatherThanSilentlyPending() {
        notifications.accept(event("order.placed", 1));
        UUID id = jdbc.sql("SELECT id FROM notifications.notifications").query(UUID.class).single();
        doThrow(new EmailDelivery.Unavailable()).when(email).send(any(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> notifications.send(new TaskExecution<>(UUID.randomUUID(), NotificationService.SEND,
                NotificationService.MAX_ATTEMPTS, orderId.toString(), new NotificationService.SendEmail(id))))
                .isInstanceOf(EmailDelivery.Unavailable.class);

        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM notifications.notifications")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void missingEmailIsExplicitlySkippedAndNeverSent() {
        jdbc.sql("UPDATE customer.customers SET email = NULL WHERE id = :id").param("id", customerId).update();
        notifications.accept(event("order.placed", 1));
        DueTasks.runAll(context);

        assertThat(status()).isEqualTo("SKIPPED");
        verifyNoInteractions(email);
    }

    private IntegrationEvent event(String type, long sequence) {
        return new IntegrationEvent(UUID.randomUUID(), type, orderId.toString(), sequence, Instant.now(),
                orderId.toString(), json.valueToTree(Map.of("order_id", orderId, "customer_id", customerId)));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM notifications.notifications").query(String.class).single();
    }
}