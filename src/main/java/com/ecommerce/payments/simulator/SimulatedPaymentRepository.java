package com.ecommerce.payments.simulator;

import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.simulator.SimulatedPayment.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the simulator's payments and refunds, one payment per order and one refund per order and reason. */
@Repository
class SimulatedPaymentRepository {

    private final JdbcClient jdbc;

    SimulatedPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that the order's payment will not be created; false if the order already has a record. */
    boolean refuse(UUID orderId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO payments.simulated_payments (order_id, status, version, created_at, updated_at)
                        VALUES (:orderId, 'CREATION_REFUSED', 1, :now, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("orderId", orderId)
                .param("now", utc(now))
                .update() == 1;
    }

    /** Creates the order's payment unless it has a record already. */
    void createIfAbsent(UUID orderId, UUID paymentId, long amountPaise, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO payments.simulated_payments (order_id, payment_id, amount_paise, status, expires_at,
                                                                 version, created_at, updated_at)
                        VALUES (:orderId, :paymentId, :amount, 'REQUIRES_PAYMENT', :expiresAt, 1, :now, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("amount", amountPaise)
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(now))
                .update();
    }

    /** Locks the order's payment record until the transaction ends. */
    Optional<SimulatedPayment> lock(UUID orderId) {
        return jdbc.sql("""
                        SELECT order_id, payment_id, amount_paise, status, expires_at, version
                        FROM payments.simulated_payments WHERE order_id = :orderId FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query(SimulatedPaymentRepository::payment)
                .optional();
    }

    SimulatedPayment setStatus(UUID orderId, Status status, Instant now) {
        return jdbc.sql("""
                        UPDATE payments.simulated_payments
                        SET status = :status, version = version + 1, updated_at = :now
                        WHERE order_id = :orderId
                        RETURNING order_id, payment_id, amount_paise, status, expires_at, version
                        """)
                .param("status", status.name())
                .param("now", utc(now))
                .param("orderId", orderId)
                .query(SimulatedPaymentRepository::payment)
                .single();
    }

    /** The order's refund for this reason, created with these values if it has none. */
    Refund refund(UUID orderId, RefundReason reason, UUID refundId, long amountPaise, Instant now) {
        jdbc.sql("""
                        INSERT INTO payments.simulated_refunds (order_id, reason, refund_id, amount_paise, created_at)
                        VALUES (:orderId, :reason, :refundId, :amount, :now)
                        ON CONFLICT DO NOTHING
                        """)
                .param("orderId", orderId)
                .param("reason", reason.name())
                .param("refundId", refundId)
                .param("amount", amountPaise)
                .param("now", utc(now))
                .update();
        return jdbc.sql("""
                        SELECT refund_id, amount_paise FROM payments.simulated_refunds
                        WHERE order_id = :orderId AND reason = :reason
                        """)
                .param("orderId", orderId)
                .param("reason", reason.name())
                .query((row, rowNumber) -> new Refund(row.getObject("refund_id", UUID.class),
                        row.getLong("amount_paise")))
                .single();
    }

    record Refund(UUID refundId, long amountPaise) {
    }

    private static SimulatedPayment payment(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
        return new SimulatedPayment(
                row.getObject("order_id", UUID.class),
                row.getObject("payment_id", UUID.class),
                row.getObject("amount_paise", Long.class),
                Status.valueOf(row.getString("status")),
                expiresAt == null ? null : expiresAt.toInstant(),
                row.getLong("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
