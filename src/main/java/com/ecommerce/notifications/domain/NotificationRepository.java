package com.ecommerce.notifications.domain;

import com.ecommerce.platform.IntegrationEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean advance(UUID orderId, long sequence) {
        return jdbc.sql("""
                        INSERT INTO notifications.order_progress AS progress (order_id, last_sequence)
                        VALUES (:orderId, :sequence)
                        ON CONFLICT (order_id) DO UPDATE SET last_sequence = EXCLUDED.last_sequence
                        WHERE progress.last_sequence < EXCLUDED.last_sequence
                        """)
                .param("orderId", orderId).param("sequence", sequence).update() == 1;
    }

    boolean queue(UUID id, IntegrationEvent event, UUID orderId, UUID customerId, String subject, String body) {
        return jdbc.sql("""
                        INSERT INTO notifications.notifications (id, order_id, customer_id, kind, subject, body)
                        VALUES (:id, :orderId, :customerId, :kind, :subject, :body)
                        ON CONFLICT (order_id, kind) DO NOTHING
                        """)
                .param("id", id).param("orderId", orderId).param("customerId", customerId)
                .param("kind", event.type()).param("subject", subject).param("body", body).update() == 1;
    }

    Optional<Pending> pending(UUID id) {
        return jdbc.sql("""
                        SELECT id, customer_id, subject, body FROM notifications.notifications
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .query((row, rowNumber) -> new Pending(row.getObject("id", UUID.class),
                        row.getObject("customer_id", UUID.class), row.getString("subject"), row.getString("body")))
                .optional();
    }

    void record(UUID id, String status, int attempt, String error) {
        jdbc.sql("""
                        UPDATE notifications.notifications
                        SET status = :status, attempts = greatest(attempts, :attempt), last_error = :error,
                            updated_at = now(), finished_at = CASE WHEN :status = 'PENDING' THEN NULL ELSE now() END
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id).param("status", status).param("attempt", attempt).param("error", error).update();
    }

    record Pending(UUID id, UUID customerId, String subject, String body) {
    }
}