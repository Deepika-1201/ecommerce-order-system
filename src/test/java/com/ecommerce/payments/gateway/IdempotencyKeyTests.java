package com.ecommerce.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.ordering.OrderingTest;
import com.ecommerce.payments.PaymentMessages.CancelPayment;
import com.ecommerce.payments.PaymentMessages.CreatePayment;
import com.ecommerce.payments.PaymentMessages.PaymentCancelRefused;
import com.ecommerce.payments.PaymentMessages.PaymentCancelled;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.payments.gateway.FakePaymentGateway.KeyedCall;
import com.ecommerce.payments.gateway.PaymentGateway.NewRefund;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The idempotency keys Payments sends (LLD §7.3): one per order and operation, so a retry can never make a second
 * payment or refund, and a cancel key that changes with the gateway's version, so a refused cancel can be sent again.
 */
class IdempotencyKeyTests extends OrderingTest {

    private static final Class<?>[] ANSWERS = {PaymentCreated.class, PaymentCancelRefused.class,
        PaymentCancelled.class, PaymentSucceeded.class, RefundInitiated.class};

    @Autowired
    private FakePaymentGateway gateway;

    @Test
    void creationAndItsCheckoutHaveKeysOfTheirOrder() {
        UUID order = created();

        assertThat(keys(order)).containsExactly(order + ":payment", order + ":checkout");
    }

    @Test
    void aCreationWhoseAnswerWasLostIsAskedAgainWithTheSameKeyAndRequest() {
        UUID order = UUID.randomUUID();
        payments.loseCreationAnswer(order);
        command(order, new CreatePayment(order, UUID.randomUUID(), 123_400, Instant.now().plus(Duration.ofMinutes(15))));
        jdbc.sql("UPDATE platform.scheduled_tasks SET run_at = now() WHERE status = 'PENDING'").update();
        deliverExcept(ANSWERS);

        List<KeyedCall> calls = calls(order);
        assertThat(calls).extracting(KeyedCall::idempotencyKey)
                .containsExactly(order + ":payment", order + ":payment", order + ":checkout");
        assertThat(calls.get(1).request()).isEqualTo(calls.get(0).request());
    }

    @Test
    void aCancelKeyCarriesTheVersionItWasSentAt() {
        UUID order = created();
        payments.startAttempt(order);
        command(order, new CancelPayment(order));

        payments.failAttempt(order);
        deliverExcept(ANSWERS);

        assertThat(keys(order)).containsExactly(order + ":payment", order + ":checkout", order + ":cancel:0",
                order + ":cancel:2");
    }

    @Test
    void aRefundHasTheKeyOfItsOrderAndReasonAndNamesItselfSo() {
        UUID order = created();
        payments.succeed(order);
        deliverExcept(ANSWERS);

        command(order, new RefundPayment(order, 123_400, RefundReason.ORDER_CANCELLED));

        KeyedCall refund = calls(order).getLast();
        assertThat(refund.idempotencyKey()).isEqualTo(order + ":refund:ORDER_CANCELLED");
        assertThat(refund.request()).isEqualTo(new NewRefund(123_400, "order_cancelled", order + ":ORDER_CANCELLED"));
    }

    private UUID created() {
        UUID order = UUID.randomUUID();
        command(order, new CreatePayment(order, UUID.randomUUID(), 123_400, Instant.now().plus(Duration.ofMinutes(15))));
        return order;
    }

    private void command(UUID order, Object command) {
        publish(command, "order", order);
        deliverExcept(ANSWERS);
    }

    private List<String> keys(UUID order) {
        return calls(order).stream().map(KeyedCall::idempotencyKey).toList();
    }

    private List<KeyedCall> calls(UUID order) {
        return gateway.keyedCalls().stream().filter(call -> call.idempotencyKey().startsWith(order + ":")).toList();
    }
}
