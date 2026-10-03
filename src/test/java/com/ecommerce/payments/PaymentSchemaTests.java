package com.ecommerce.payments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The payments schema refuses what no update produces (database.md §9): a status without the gateway's payment, a
 * created payment without its checkout session, a version without a status, a refund's reason that does not fit who
 * initiated it, and a second refund for the same order and reason.
 */
class PaymentSchemaTests extends OrderingTest {

    private static final Class<?>[] ANSWERS = {PaymentCreated.class, PaymentSucceeded.class, RefundInitiated.class};

    @Test
    void aStatusAndAVersionExactlyWhenTheGatewayHasThePayment() {
        UUID creating = creating();
        UUID created = created();

        assertRejected("payment_records_status",
                "UPDATE payments.payment_records SET status = 'PROCESSING', gateway_version = 1 WHERE order_id = :id",
                creating);
        assertRejected("payment_records_status",
                "UPDATE payments.payment_records SET gateway_payment_id = 'pay_unknown' WHERE order_id = :id",
                creating);
        assertRejected("payment_records_version",
                "UPDATE payments.payment_records SET gateway_version = 0 WHERE order_id = :id", creating);
        assertRejected("payment_records_version",
                "UPDATE payments.payment_records SET gateway_version = NULL WHERE order_id = :id", created);
    }

    @Test
    void exactlyACreatedPaymentHasItsCheckoutSession() {
        UUID creating = creating();
        UUID created = created();

        assertRejected("payment_records_creation",
                "UPDATE payments.payment_records SET creation = 'CREATED' WHERE order_id = :id", creating);
        assertRejected("payment_records_creation",
                "UPDATE payments.payment_records SET checkout_url = 'https://checkout.invalid' WHERE order_id = :id",
                creating);
        assertRejected("payment_records_creation",
                "UPDATE payments.payment_records SET checkout_url = NULL WHERE order_id = :id", created);
    }

    @Test
    void aRefundsReasonFitsWhoInitiatedIt() {
        UUID refunded = refunded();

        assertRejected("refunds_reason", "UPDATE payments.refunds SET reason = NULL WHERE order_id = :id", refunded);
        assertRejected("refunds_reason",
                "UPDATE payments.refunds SET initiated_by = 'SYSTEM_LATE_SUCCESS' WHERE order_id = :id", refunded);
        assertRejected("refunds_reason",
                "UPDATE payments.refunds SET initiated_by = 'SYSTEM_LATE_SUCCESS', reason = NULL WHERE order_id = :id",
                refunded);
        assertRejected("refunds_reason",
                "UPDATE payments.refunds SET initiated_by = 'SYSTEM_DUPLICATE_SUCCESS' WHERE order_id = :id",
                refunded);
    }

    @Test
    void aRefundIsRequestedAndUnversionedExactlyUntilTheGatewayHasIt() {
        UUID refunded = refunded();

        assertRejected("refunds_requested",
                "UPDATE payments.refunds SET status = 'REQUESTED' WHERE order_id = :id", refunded);
        assertRejected("refunds_requested",
                "UPDATE payments.refunds SET gateway_refund_id = NULL, gateway_version = NULL WHERE order_id = :id",
                refunded);
        assertRejected("refunds_version",
                "UPDATE payments.refunds SET gateway_version = NULL WHERE order_id = :id", refunded);
    }

    @Test
    void theSagaRefundsAnOrderOncePerReason() {
        UUID refunded = refunded();

        assertRejected("refunds_per_reason", """
                INSERT INTO payments.refunds (id, order_id, reason, amount_paise, initiated_by, status, created_at,
                                              updated_at)
                VALUES (gen_random_uuid(), :id, 'ORDER_CANCELLED', 100, 'MERCHANT', 'REQUESTED', now(), now())
                """, refunded);
    }

    /** A payment whose creation keeps timing out: its record has nothing from the gateway yet. */
    private UUID creating() {
        UUID order = UUID.randomUUID();
        payments.timeOutCreation(order);
        create(order);
        return order;
    }

    private UUID created() {
        UUID order = UUID.randomUUID();
        create(order);
        return order;
    }

    /** A paid order, refunded as the saga asks when it is cancelled. */
    private UUID refunded() {
        UUID order = created();
        payments.succeed(order);
        deliverExcept(ANSWERS);
        publish(new RefundPayment(order, 123_400, RefundReason.ORDER_CANCELLED), "order", order);
        deliverExcept(ANSWERS);
        return order;
    }

    private void create(UUID order) {
        publish(new CreatePayment(order, UUID.randomUUID(), 123_400, Instant.now().plus(Duration.ofMinutes(15))),
                "order", order);
        deliverExcept(ANSWERS);
    }

    private void assertRejected(String constraint, String sql, UUID orderId) {
        assertThatThrownBy(() -> jdbc.sql(sql).param("id", orderId).update())
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining('"' + constraint + '"');
    }
}
