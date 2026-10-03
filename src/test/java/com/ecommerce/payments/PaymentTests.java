package com.ecommerce.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.payments.PaymentMessages.CancelPayment;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCancelRefused;
import com.ecommerce.payments.PaymentMessages.PaymentCancelled;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentCreationFailed;
import com.ecommerce.payments.PaymentMessages.PaymentExpired;
import com.ecommerce.payments.PaymentMessages.PaymentFailed;
import com.ecommerce.payments.PaymentMessages.PaymentPending;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundFailed;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.PaymentMessages.RefundSucceeded;
import com.ecommerce.payments.gateway.GatewayEvents;
import com.ecommerce.platform.messaging.DueMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payments against the fake gateway (LLD §7.5, §7.6): the creation budget, a cancel sent again after the attempt, the
 * gateway's state applied by version whatever brings it, and the gateway's own refunds. Answers and events stay in the
 * outbox, where these tests read them.
 */
class PaymentTests extends OrderingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Class<?>[] ANSWERS = {PaymentCreated.class, PaymentCreationFailed.class,
        PaymentCancelled.class, PaymentCancelRefused.class, PaymentPending.class, PaymentSucceeded.class,
        PaymentFailed.class, PaymentExpired.class, RefundInitiated.class, RefundSucceeded.class, RefundFailed.class};

    @Autowired
    private GatewayEvents events;

    private final UUID orderId = UUID.randomUUID();

    @Test
    void aCreationThatKeepsTimingOutFailsOnceItsBudgetIsSpent() {
        payments.timeOutCreation(orderId);

        command(create(orderId, Duration.ofMinutes(15)));
        assertThat(answers()).isEmpty();
        assertThat(payment(orderId)).isEqualTo("CREATING");
        assertThat(taskAttempts("payments.create-payment")).as("tried, to be retried with backoff").isOne();

        budgetSpent();
        settle();

        assertThat(answers()).containsExactly("payments.payment-creation-failed");
        assertThat(payment(orderId)).isEqualTo("CREATION_FAILED");
        command(create(orderId, Duration.ofMinutes(15)));
        assertThat(answers()).as("a repeat is answered from the record")
                .containsExactly("payments.payment-creation-failed", "payments.payment-creation-failed");
    }

    @Test
    void aCreationWhoseAnswerWasLostIsRetriedWithTheSameRequest() throws InterruptedException {
        payments.loseCreationAnswer(orderId);
        command(create(orderId, Duration.ofMinutes(15)));
        assertThat(answers()).isEmpty();

        Thread.sleep(1_100);   // a request that depended on the time would now differ, and be refused for its key
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE status = 'PENDING'").update();
        settle();

        assertThat(answers()).containsExactly("payments.payment-created");
        assertThat(payment(orderId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    void aPaymentThatWouldLastLessThanAMinuteIsNotCreated() {
        command(create(orderId, Duration.ofSeconds(50)));

        assertThat(answers()).containsExactly("payments.payment-creation-failed");
        assertThat(gatewayPaymentId(orderId)).isNull();
    }

    @Test
    void aPaymentLeftWithoutACheckoutSessionIsCancelled() {
        payments.timeOutCheckout(orderId);
        command(create(orderId, Duration.ofMinutes(15)));
        assertThat(gatewayPaymentId(orderId)).as("the payment was created").isNotNull();

        budgetSpent();
        settle();

        assertThat(answers()).first().isEqualTo("payments.payment-creation-failed");
        assertThat(payment(orderId)).isEqualTo("CREATION_FAILED");
        assertThat(column("SELECT status FROM payments.payment_records WHERE order_id = :id"))
                .as("cancelled at the gateway, best effort").isEqualTo("CANCELLED");
    }

    @Test
    void aCancelRefusedDuringAnAttemptIsSentAgainWhenTheAttemptFails() {
        command(create(orderId, Duration.ofMinutes(15)));
        payments.startAttempt(orderId);

        command(new CancelPayment(orderId));
        assertThat(answers()).containsExactly("payments.payment-created", "payments.payment-cancel-refused");
        assertThat(payment(orderId)).isEqualTo("PROCESSING");

        payments.failAttempt(orderId);
        settle();

        assertThat(answers()).last().isEqualTo("payments.payment-cancelled");
        assertThat(payment(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void anOlderVersionChangesNothingAndAFinalStatusIsPublishedOnce() {
        command(create(orderId, Duration.ofMinutes(15)));
        String gatewayId = gatewayPaymentId(orderId);

        receive(paymentEvent(gatewayId, "payment.failed", "failed", 5));
        receive(paymentEvent(gatewayId, "payment.attempt_failed", "requires_payment_method", 3));
        receive(paymentEvent(gatewayId, "payment.failed", "failed", 5));
        settle();

        assertThat(payment(orderId)).isEqualTo("FAILED");
        assertThat(column("SELECT gateway_version::text FROM payments.payment_records WHERE order_id = :id"))
                .isEqualTo("5");
        assertThat(answers()).containsExactly("payments.payment-created", "payments.payment-failed");
    }

    @Test
    void aRepeatedEventIsStoredAndAppliedOnce() {
        command(create(orderId, Duration.ofMinutes(15)));
        String event = paymentEvent(gatewayPaymentId(orderId), "payment.succeeded", "succeeded", 2);

        assertThat(receive(event)).isTrue();
        assertThat(receive(event)).isFalse();
        settle();

        assertThat(count("SELECT count(*) FROM platform.webhook_inbox WHERE processed_at IS NOT NULL")).isOne();
        assertThat(answers()).containsExactly("payments.payment-created", "payments.payment-succeeded");
    }

    @Test
    void eventsAboutOtherPaymentsAndDisputesAreKeptButChangeNothing() {
        command(create(orderId, Duration.ofMinutes(15)));

        receive(paymentEvent("pay_someone_else", "payment.succeeded", "succeeded", 2));
        receive("""
                {"id": "evt_dispute_1", "type": "dispute.created", "created_at": "2026-10-03T10:00:00Z",
                 "data": {"object": {"id": "dsp_1", "object": "dispute"}}}
                """);
        settle();

        assertThat(count("SELECT count(*) FROM platform.webhook_inbox WHERE processed_at IS NOT NULL")).isEqualTo(2);
        assertThat(answers()).containsExactly("payments.payment-created");
        assertThat(payment(orderId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    void theGatewaysLateSuccessRefundIsRecordedAndPublishedWhenItEnds() {
        command(create(orderId, Duration.ofMinutes(15)));
        payments.expire(orderId);
        settle();

        payments.succeed(orderId);
        settle();
        assertThat(answers()).last().as("nothing until the refund ends").isEqualTo("payments.payment-expired");
        payments.succeedRefund(orderId);
        settle();

        JsonNode refunded = lastAnswer();
        assertThat(refunded.get("reason").asString()).isEqualTo("LATE_SUCCESS");
        assertThat(refunded.get("amount_paise").asLong()).isEqualTo(123_400);
        assertThat(answers()).last().isEqualTo("payments.refund-succeeded");
        assertThat(column("SELECT initiated_by FROM payments.refunds WHERE order_id = :id"))
                .isEqualTo("SYSTEM_LATE_SUCCESS");
        assertThat(payment(orderId)).isEqualTo("EXPIRED");
    }

    @Test
    void aSecondChargeRefundedByTheGatewayIsRecordedButNotPublished() {
        command(create(orderId, Duration.ofMinutes(15)));
        payments.succeed(orderId);
        settle();

        payments.succeed(orderId);
        payments.succeedRefund(orderId);
        settle();

        assertThat(answers()).containsExactly("payments.payment-created", "payments.payment-succeeded");
        assertThat(column("""
                SELECT initiated_by || ' ' || status || ' ' || coalesce(reason, 'no reason')
                FROM payments.refunds WHERE order_id = :id
                """)).isEqualTo("SYSTEM_DUPLICATE_SUCCESS SUCCEEDED no reason");
    }

    @Test
    void aRefundsEndThatOvertakesItsCreationIsMatchedByThisSystemsRefundId() {
        command(create(orderId, Duration.ofMinutes(15)));
        payments.succeed(orderId);
        settle();
        publish(new RefundPayment(orderId, 123_400, RefundReason.ORDER_CANCELLED), "order", orderId);
        DueMessages.deliverAllExcept(context, ANSWERS);
        jdbc.sql("DELETE FROM platform.scheduled_tasks WHERE type = 'payments.refund-payment'").update();

        receive("""
                {"id": "evt_refund_1", "type": "refund.succeeded", "created_at": "2026-10-03T10:00:00Z",
                 "data": {"object": {"id": "rfnd_overtaking", "object": "refund", "payment_id": "%s",
                   "attempt_id": "att_1", "amount": 123400, "currency": "INR", "status": "succeeded",
                   "merchant_refund_id": "%s:ORDER_CANCELLED", "initiated_by": "merchant", "provider": "FAKE_PSP",
                   "created_at": "2026-10-03T10:00:00Z", "updated_at": "2026-10-03T10:00:00Z", "version": 2}}}
                """.formatted(gatewayPaymentId(orderId), orderId));
        settle();
        command(new RefundPayment(orderId, 123_400, RefundReason.ORDER_CANCELLED));

        assertThat(answers()).containsExactly("payments.payment-created", "payments.payment-succeeded",
                "payments.refund-succeeded", "payments.refund-initiated");
        assertThat(column("SELECT gateway_refund_id || ' ' || status FROM payments.refunds WHERE order_id = :id"))
                .isEqualTo("rfnd_overtaking SUCCEEDED");
    }

    @Test
    void answersComeFromThePaymentAndNameTheCommandAsTheirCause() {
        command(create(orderId, Duration.ofMinutes(15)));

        assertThat(envelope("payments.payment-created", "causation_id"))
                .isEqualTo(envelope("payments.create-payment", "message_id"));
        assertThat(envelope("payments.payment-created", "correlation_id")).isEqualTo(orderId.toString());
        assertThat(column("SELECT aggregate_type FROM platform.outbox WHERE type = 'payments.payment-created' "
                + "AND aggregate_id = CAST(:id AS text)")).isEqualTo("payment");
    }

    private static CreatePayment create(UUID order, Duration payable) {
        return new CreatePayment(order, CUSTOMER, 123_400, Instant.now().plus(payable));
    }

    /** Sends a saga command for the order and runs what follows, leaving every answer in the outbox. */
    private void command(Object command) {
        publish(command, "order", orderId);
        settle();
    }

    private void settle() {
        deliverExcept(ANSWERS);
    }

    /** The creation budget's 60 seconds are over, and the retry is due. */
    private void budgetSpent() {
        jdbc.sql("UPDATE payments.payment_records SET created_at = created_at - interval '61 seconds' "
                        + "WHERE order_id = :id")
                .param("id", orderId)
                .update();
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE status = 'PENDING'").update();
    }

    private boolean receive(String event) {
        return events.receive(event);
    }

    private static String paymentEvent(String gatewayId, String type, String status, long version) {
        return """
                {"id": "evt_%s", "type": "%s", "created_at": "2026-10-03T10:00:00Z",
                 "data": {"object": {"id": "%s", "object": "payment", "merchant_order_id": "order", "amount": 123400,
                   "currency": "INR", "status": "%s", "capture_method": "automatic", "amount_captured": 0,
                   "amount_refunded": 0, "attempt_count": 1, "expires_at": "2026-10-03T10:15:00Z",
                   "created_at": "2026-10-03T10:00:00Z", "updated_at": "2026-10-03T10:00:00Z", "version": %d}}}
                """.formatted(UUID.randomUUID().toString().replace("-", ""), type, gatewayId, status, version);
    }

    /** The types of what Payments published for the order, oldest first. */
    private List<String> answers() {
        return jdbc.sql("""
                        SELECT type FROM platform.outbox
                        WHERE aggregate_id = :id AND aggregate_type = 'payment' ORDER BY id
                        """)
                .param("id", orderId.toString())
                .query(String.class)
                .list();
    }

    private JsonNode lastAnswer() {
        return JSON.readTree(jdbc.sql("""
                        SELECT envelope FROM platform.outbox
                        WHERE aggregate_id = :id AND aggregate_type = 'payment' ORDER BY id DESC LIMIT 1
                        """)
                .param("id", orderId.toString())
                .query(String.class)
                .single()).get("data");
    }

    private String envelope(String type, String field) {
        return JSON.readTree(jdbc.sql("SELECT envelope FROM platform.outbox WHERE type = :type AND aggregate_id = :id")
                .param("type", type)
                .param("id", orderId.toString())
                .query(String.class)
                .single()).get(field).asString();
    }

    private int taskAttempts(String type) {
        return jdbc.sql("SELECT attempts FROM platform.scheduled_tasks WHERE type = :type")
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private String gatewayPaymentId(UUID order) {
        return jdbc.sql("SELECT gateway_payment_id FROM payments.payment_records WHERE order_id = :id")
                .param("id", order)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String column(String sql) {
        return jdbc.sql(sql).param("id", orderId).query(String.class).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
