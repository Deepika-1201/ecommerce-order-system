package com.ecommerce.payments.domain;

import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.gateway.GatewayPayment;
import com.ecommerce.payments.gateway.GatewayRefund;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for payment records and refunds (LLD §7.4). Refunds change under their payment record's lock, and every change
 * to either counts in the record's {@code version}, the sequence of what Payments publishes.
 */
@Repository
class PaymentRepository {

    private static final String PAYMENT_COLUMNS = """
            id, order_id, customer_id, amount_paise, expires_at, creation, gateway_payment_id, status, gateway_version,
            checkout_url, cancel_requested, created_at, version""";
    private static final String REFUND_COLUMNS = """
            id, order_id, reason, amount_paise, initiated_by, gateway_refund_id, status, gateway_version""";

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the order's payment as being created, unless the order has one. */
    void insertIfAbsent(UUID id, CreatePayment command, Instant now) {
        jdbc.sql("""
                        INSERT INTO payments.payment_records (id, order_id, customer_id, amount_paise, expires_at,
                                                              creation, created_at, updated_at, version)
                        VALUES (:id, :orderId, :customerId, :amount, :expiresAt, 'CREATING', :now, :now, 1)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", id)
                .param("orderId", command.orderId())
                .param("customerId", command.customerId())
                .param("amount", command.amountPaise())
                .param("expiresAt", utc(command.expiresAt()))
                .param("now", utc(now))
                .update();
    }

    Optional<PaymentRecord> find(UUID orderId) {
        return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments.payment_records WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(PaymentRepository::payment)
                .optional();
    }

    /** Locks the order's record until the transaction ends. */
    Optional<PaymentRecord> lock(UUID orderId) {
        return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments.payment_records WHERE order_id = :orderId "
                        + "FOR UPDATE")
                .param("orderId", orderId)
                .query(PaymentRepository::payment)
                .optional();
    }

    /** Locks the record of the gateway's payment, if this system created it. */
    Optional<PaymentRecord> lockByGatewayId(String gatewayPaymentId) {
        return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments.payment_records "
                        + "WHERE gateway_payment_id = :gatewayPaymentId FOR UPDATE")
                .param("gatewayPaymentId", gatewayPaymentId)
                .query(PaymentRepository::payment)
                .optional();
    }

    /** The gateway created the payment: its id, and its status if that is news. */
    PaymentRecord gatewayCreated(UUID orderId, GatewayPayment payment, Instant now) {
        return update("""
                        gateway_payment_id = :gatewayPaymentId,
                        status = CASE WHEN gateway_version IS NULL OR :gatewayVersion > gateway_version
                                      THEN :status ELSE status END,
                        gateway_version = CASE WHEN gateway_version IS NULL OR :gatewayVersion > gateway_version
                                               THEN :gatewayVersion ELSE gateway_version END""", orderId, now)
                .param("gatewayPaymentId", payment.id())
                .param("status", payment.status().name())
                .param("gatewayVersion", payment.version())
                .query(PaymentRepository::payment)
                .single();
    }

    PaymentRecord created(UUID orderId, String checkoutUrl, Instant now) {
        return update("creation = 'CREATED', checkout_url = :checkoutUrl", orderId, now)
                .param("checkoutUrl", checkoutUrl)
                .query(PaymentRepository::payment)
                .single();
    }

    PaymentRecord creationFailed(UUID orderId, Instant now) {
        return update("creation = 'FAILED'", orderId, now).query(PaymentRepository::payment).single();
    }

    PaymentRecord applyStatus(UUID orderId, GatewayPayment.Status status, long gatewayVersion, Instant now) {
        return update("status = :status, gateway_version = :gatewayVersion", orderId, now)
                .param("status", status.name())
                .param("gatewayVersion", gatewayVersion)
                .query(PaymentRepository::payment)
                .single();
    }

    PaymentRecord requestCancel(UUID orderId, Instant now) {
        return update("cancel_requested = true", orderId, now).query(PaymentRepository::payment).single();
    }

    /** Counts a refund's change in the record's version; returns the new version. */
    long countChange(UUID orderId, Instant now) {
        return update("", orderId, now).query(PaymentRepository::payment).single().version();
    }

    /** Records the saga's refund for this reason, unless the order has one. */
    void insertRefundIfAbsent(UUID id, UUID orderId, RefundReason reason, long amountPaise, Instant now) {
        jdbc.sql("""
                        INSERT INTO payments.refunds (id, order_id, reason, amount_paise, initiated_by, status,
                                                      created_at, updated_at)
                        VALUES (:id, :orderId, :reason, :amount, 'MERCHANT', 'REQUESTED', :now, :now)
                        ON CONFLICT (order_id, reason) WHERE initiated_by = 'MERCHANT' DO NOTHING
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("reason", reason.name())
                .param("amount", amountPaise)
                .param("now", utc(now))
                .update();
    }

    /** Records a refund the gateway made on its own, as it reported it. */
    RefundRecord insertGatewayRefund(UUID id, UUID orderId, GatewayRefund refund, RefundReason reason, Instant now) {
        return jdbc.sql("""
                        INSERT INTO payments.refunds (id, order_id, reason, amount_paise, initiated_by,
                                                      gateway_refund_id, status, gateway_version, created_at,
                                                      updated_at)
                        VALUES (:id, :orderId, :reason, :amount, :initiatedBy, :gatewayRefundId, :status,
                                :gatewayVersion, :now, :now)
                        RETURNING\s""" + REFUND_COLUMNS)
                .param("id", id)
                .param("orderId", orderId)
                .param("reason", reason == null ? null : reason.name())
                .param("amount", refund.amountPaise())
                .param("initiatedBy", refund.initiatedBy().name())
                .param("gatewayRefundId", refund.id())
                .param("status", RefundRecord.Status.of(refund.status()).name())
                .param("gatewayVersion", refund.version())
                .param("now", utc(now))
                .query(PaymentRepository::refund)
                .single();
    }

    Optional<RefundRecord> refund(UUID orderId, RefundReason reason) {
        return jdbc.sql("SELECT " + REFUND_COLUMNS + " FROM payments.refunds "
                        + "WHERE order_id = :orderId AND reason = :reason AND initiated_by = 'MERCHANT'")
                .param("orderId", orderId)
                .param("reason", reason.name())
                .query(PaymentRepository::refund)
                .optional();
    }

    Optional<RefundRecord> refundByGatewayId(String gatewayRefundId) {
        return jdbc.sql("SELECT " + REFUND_COLUMNS + " FROM payments.refunds WHERE gateway_refund_id = :id")
                .param("id", gatewayRefundId)
                .query(PaymentRepository::refund)
                .optional();
    }

    RefundRecord updateRefund(UUID refundId, String gatewayRefundId, RefundRecord.Status status, long gatewayVersion,
                              Instant now) {
        return jdbc.sql("""
                        UPDATE payments.refunds
                        SET gateway_refund_id = :gatewayRefundId, status = :status, gateway_version = :gatewayVersion,
                            updated_at = :now
                        WHERE id = :id
                        RETURNING\s""" + REFUND_COLUMNS)
                .param("gatewayRefundId", gatewayRefundId)
                .param("status", status.name())
                .param("gatewayVersion", gatewayVersion)
                .param("now", utc(now))
                .param("id", refundId)
                .query(PaymentRepository::refund)
                .single();
    }

    private JdbcClient.StatementSpec update(String assignments, UUID orderId, Instant now) {
        return jdbc.sql("UPDATE payments.payment_records SET " + (assignments.isEmpty() ? "" : assignments + ", ")
                        + "version = version + 1, updated_at = :now WHERE order_id = :orderId RETURNING "
                        + PAYMENT_COLUMNS)
                .param("orderId", orderId)
                .param("now", utc(now));
    }

    private static PaymentRecord payment(ResultSet row, int rowNumber) throws SQLException {
        String status = row.getString("status");
        return new PaymentRecord(
                row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("customer_id", UUID.class),
                row.getLong("amount_paise"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                PaymentRecord.Creation.valueOf(row.getString("creation")),
                row.getString("gateway_payment_id"),
                status == null ? null : GatewayPayment.Status.valueOf(status),
                row.getObject("gateway_version", Long.class),
                row.getString("checkout_url"),
                row.getBoolean("cancel_requested"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getLong("version"));
    }

    private static RefundRecord refund(ResultSet row, int rowNumber) throws SQLException {
        String reason = row.getString("reason");
        return new RefundRecord(
                row.getObject("id", UUID.class),
                row.getObject("order_id", UUID.class),
                reason == null ? null : RefundReason.valueOf(reason),
                row.getLong("amount_paise"),
                GatewayRefund.Initiator.valueOf(row.getString("initiated_by")),
                row.getString("gateway_refund_id"),
                RefundRecord.Status.valueOf(row.getString("status")),
                row.getObject("gateway_version", Long.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
