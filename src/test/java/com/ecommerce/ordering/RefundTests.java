package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * Refunds and payment checks through the outbox and the API (LLD §7.8, §7.9): a refund's end reaches the order, a
 * failed one raises an alert, and the customer's return from the checkout asks for the payment's outcome at once.
 */
@ExtendWith(OutputCaptureExtension.class)
class RefundTests extends OrderingTest {

    @Test
    void aCancelledOrdersRefundEndsSucceeded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 1, null);
        expect(202, cancel(asha, orderId));
        deliver();
        assertThat(refund(orderId).get("status").asString()).isEqualTo("INITIATED");

        payments.succeedRefund(orderId);
        deliver();

        JsonNode refund = refund(orderId);
        assertThat(refund.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(refund.get("amount_paise").asLong()).isEqualTo(grandTotal(orderId));
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(step(orderId)).isEqualTo("DONE");
    }

    @Test
    void aFailedRefundIsShownAndAlerted(CapturedOutput output) {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 1, null);
        shipments.handOver(orderId);
        deliver();
        shipments.startReturn(orderId);
        shipments.completeReturn(orderId);
        deliver();
        assertThat(status(orderId)).isEqualTo("RETURNED_TO_ORIGIN");

        payments.failRefund(orderId);
        deliver();

        assertThat(refund(orderId).get("status").asString()).isEqualTo("FAILED");
        assertThat(output.getOut()).contains("order_refund_failed: order " + orderId + ", RETURNED_TO_ORIGIN refund of "
                + grandTotal(orderId) + " paise");
    }

    @Test
    void theCustomersReturnFromTheCheckoutAsksForTheOutcomeNow() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        Instant deadline = deadline(orderId);
        payments.succeed(orderId);
        jdbc.sql("DELETE FROM platform.scheduled_tasks WHERE type = 'payments.apply-gateway-event'").update();

        JsonNode answered = expect(202, call("POST", "/v1/me/orders/" + orderId + "/payment-check", asha, null));

        assertThat(answered.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(pending(orderId)).containsExactly("payments.check-payment");
        assertThat(deadline(orderId)).as("still the hold's expiry").isEqualTo(deadline);
        deliver();
        assertThat(status(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void aPendingPaymentOnReturnIsAwaitedUntilTheHoldExpires() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        Instant deadline = deadline(orderId);
        long version = version(orderId);

        expect(202, call("POST", "/v1/me/orders/" + orderId + "/payment-check", asha, null));
        expect(202, call("POST", "/v1/me/orders/" + orderId + "/payment-check", asha, null));
        deliver();

        assertThat(status(orderId)).isEqualTo("AWAITING_PAYMENT");
        assertThat(deadline(orderId)).isEqualTo(deadline);
        assertThat(version(orderId)).as("two checks sent, the pending answers changed nothing").isEqualTo(version + 2);
        assertThat(Duration.between(Instant.now(), deadline)).isGreaterThan(Duration.ofMinutes(45));
    }

    @Test
    void aCheckOnlyAsksWhileTheOrderWaitsForItsPayment() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID confirmed = confirmed(sku, 1, null);
        long version = version(confirmed);

        JsonNode answered = expect(202, call("POST", "/v1/me/orders/" + confirmed + "/payment-check", asha, null));

        assertThat(answered.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(version(confirmed)).isEqualTo(version);
        assertThat(pending(confirmed)).isEmpty();
        assertCode(call("POST", "/v1/me/orders/" + confirmed + "/payment-check", ravi, null), 404, "not_found");
        assertCode(call("POST", "/v1/me/orders/" + UUID.randomUUID() + "/payment-check", asha, null), 404,
                "not_found");
        assertThat(checksHandled()).isZero();
    }

    private JsonNode refund(UUID orderId) {
        return order(asha, orderId).get("refund");
    }

    private Instant deadline(UUID orderId) {
        return jdbc.sql("SELECT deadline_at FROM ordering.order_processes WHERE order_id = :id")
                .param("id", orderId)
                .query((row, n) -> row.getObject("deadline_at", OffsetDateTime.class).toInstant())
                .single();
    }

    private int checksHandled() {
        return jdbc.sql("SELECT count(*) FROM platform.processed_messages WHERE consumer = 'payments.check-payment'")
                .query(Integer.class)
                .single();
    }
}
