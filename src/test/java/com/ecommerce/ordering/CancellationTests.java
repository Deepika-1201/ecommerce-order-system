package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ecommerce.inventory.StockMessages.CommitReservation;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Cancelling orders through the API, by customers and support, in every status (LLD §6.6). */
class CancellationTests extends OrderingTest {

    @Test
    void aPlacedOrderIsCancelledWhenItsStepAnswers() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 2, null);

        JsonNode cancelling = expect(202, cancel(asha, orderId));

        assertThat(cancelling.get("status").asString()).isEqualTo("CANCELLING");
        assertThat(step(orderId)).as("the step goes on").isEqualTo("RESERVING_STOCK");
        deliver();
        JsonNode cancelled = order(asha, orderId);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("CUSTOMER");
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertThat(payment(orderId)).as("never created").isNull();
        assertThat(pending(orderId)).isEmpty();
    }

    @Test
    void anOrderAwaitingPaymentIsCancelledWithItsPayment() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = awaitingPayment(sku, 1, "WELCOME");

        JsonNode cancelling = expect(202, cancel(asha, orderId));

        assertThat(cancelling.get("status").asString()).isEqualTo("CANCELLING");
        assertThat(cancelling.has("checkout_url")).isFalse();
        assertThat(pending(orderId)).containsExactly("payments.cancel-payment");
        deliver();
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
        assertThat(payment(orderId)).isEqualTo("CANCELLED");
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertThat(redemption(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertCoupon(couponId, 0, 0);
        assertThat(refunds(orderId)).isEmpty();
    }

    @Test
    void aCancellationRefusedDuringAnAttemptToPayRefundsTheAttemptsSuccess() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.startAttempt(orderId);

        expect(202, cancel(asha, orderId));
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLING");
        assertThat(step(orderId)).isEqualTo("AWAITING_PAYMENT_OUTCOME");
        assertStock(sku, 5, 1);

        payments.succeed(orderId);
        deliver();

        JsonNode cancelled = order(asha, orderId);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("CUSTOMER");
        assertThat(cancelled.get("refund").get("status").asString()).isEqualTo("INITIATED");
        assertThat(refunds(orderId)).containsExactly(entry("ORDER_CANCELLED", grandTotal(orderId)));
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
    }

    @Test
    void aCancellationRefusedDuringAnAttemptToPayReleasesEverythingIfTheAttemptFails() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.startAttempt(orderId);
        expect(202, cancel(asha, orderId));
        deliver();

        payments.fail(orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
        assertThat(refunds(orderId)).isEmpty();
        assertStock(sku, 5, 0);
    }

    @Test
    void aCancellationJustAfterThePaymentSucceededRefundsIt() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.succeed(orderId);
        deliverExcept(CommitReservation.class);
        assertThat(step(orderId)).isEqualTo("COMMITTING_STOCK");

        JsonNode cancelling = expect(202, cancel(asha, orderId));

        assertThat(cancelling.get("status").asString()).isEqualTo("CANCELLING");
        assertThat(step(orderId)).as("waits for the commit").isEqualTo("COMMITTING_STOCK");
        deliver();
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
        assertThat(refunds(orderId)).containsExactly(entry("ORDER_CANCELLED", grandTotal(orderId)));
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertThat(shipment(orderId)).as("never booked").isNull();
        assertStock(sku, 5, 0);
    }

    @Test
    void aPaymentThatSucceedsJustBeforePaymentsHearsOfTheCancellationIsRefunded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);

        expect(202, cancel(asha, orderId));
        payments.succeed(orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
        assertThat(payment(orderId)).isEqualTo("SUCCEEDED");
        assertThat(refunds(orderId)).containsExactly(entry("ORDER_CANCELLED", grandTotal(orderId)));
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
    }

    @Test
    void aConfirmedOrderIsCancelledWithItsShipmentAndRefunded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = confirmed(sku, 2, "WELCOME");
        assertCoupon(couponId, 0, 1);

        expect(202, cancel(asha, orderId));
        assertThat(pending(orderId)).containsExactly("fulfillment.cancel-shipment");
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
        assertThat(shipment(orderId)).isEqualTo("CANCELLED");
        assertThat(refunds(orderId)).containsExactly(entry("ORDER_CANCELLED", grandTotal(orderId)));
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertCoupon(couponId, 0, 0);
    }

    @Test
    void aCancellationThatLosesTheRaceWithTheHandoverIsRefused() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 2, null);

        expect(202, cancel(asha, orderId));
        shipments.handOver(orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("SHIPPED");
        assertThat(step(orderId)).isEqualTo("AWAITING_DELIVERY");
        assertThat(shipment(orderId)).isEqualTo("HANDED_OVER");
        assertThat(refunds(orderId)).isEmpty();
        assertThat(reservation(orderId)).isEqualTo("FULFILLED");
        assertStock(sku, 3, 0);
    }

    @Test
    void cancellingAnOrderAgainChangesNothing() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        expect(202, cancel(asha, orderId));
        long version = version(orderId);

        assertThat(expect(202, cancel(asha, orderId)).get("status").asString()).isEqualTo("CANCELLING");
        assertThat(version(orderId)).isEqualTo(version);
        deliver();
        JsonNode cancelled = expect(202, cancel(asha, orderId));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("CUSTOMER");
        assertThat(pending(orderId)).isEmpty();
    }

    @Test
    void shippedDeliveredAndRejectedOrdersCannotBeCancelled() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 1, null);
        shipments.handOver(orderId);
        deliver();

        assertCode(cancel(asha, orderId), 409, "order_invalid_state");
        shipments.deliver(orderId);
        deliver();
        assertCode(cancel(asha, orderId), 409, "order_invalid_state");
        assertThat(status(orderId)).isEqualTo("DELIVERED");

        String unstocked = product("Rare print", 10_000, 0);
        UUID rejected = placeOrder(ravi, unstocked, 1, null);
        deliver();
        assertCode(cancel(ravi, rejected), 409, "order_invalid_state");
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheCancellation() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        String path = "/v1/me/orders/" + orderId + "/cancel";
        String key = newKey();
        JsonNode first = expect(202, call("POST", path, asha, null, "Idempotency-Key", key));
        deliver();

        HttpResponse<String> replay = call("POST", path, asha, null, "Idempotency-Key", key);

        assertThat(expect(202, replay)).isEqualTo(first);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertCode(call("POST", path, asha, null), 400, "idempotency_key_required");
    }

    @Test
    void supportCancelsWithAReasonCodeAndTheRequestIsAudited() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);

        JsonNode cancelling = expect(202, supportCancel(orderId,
                "{\"reason_code\": \"SUSPECTED_FRAUD\", \"note\": \"  Card flagged by the bank \"}"));

        assertThat(cancelling.get("status").asString()).isEqualTo("CANCELLING");
        assertThat(cancelling.get("customer_id").asString()).isEqualTo(customerOf(orderId).toString());
        assertThat(jdbc.sql("""
                        SELECT actor_type, actor_id, action, target_type, target_id, reason,
                               details ->> 'note' AS note
                        FROM platform.audit_log WHERE action LIKE 'ordering.%'
                        """)
                .query((row, n) -> List.of(row.getString("actor_type"), row.getString("actor_id"),
                        row.getString("action"), row.getString("target_type"), row.getString("target_id"),
                        row.getString("reason"), row.getString("note")))
                .list())
                .containsExactly(List.of("staff", supportSubject, "ordering.order.cancel-requested", "order",
                        orderId.toString(), "SUSPECTED_FRAUD", "Card flagged by the bank"));
        deliver();
        JsonNode cancelled = expect(200, call("GET", "/v1/support/orders/" + orderId, support, null));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reason").asString()).isEqualTo("SUPPORT");
        assertStock(sku, 5, 0);
    }

    @Test
    void anotherReasonNeedsANote() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);

        assertCode(supportCancel(orderId, "{\"reason_code\": \"OTHER\"}"), 400, "invalid_request");
        assertCode(supportCancel(orderId, "{\"reason_code\": \"OTHER\", \"note\": \"   \"}"), 400,
                "invalid_request");
        assertThat(status(orderId)).isEqualTo("PLACED");

        expect(202, supportCancel(orderId, "{\"reason_code\": \"OTHER\", \"note\": \"Placed twice by mistake\"}"));
        assertThat(auditEntries()).isEqualTo(1);
    }

    @Test
    void supportCancellingAnOrderTheCustomerIsCancellingAlreadyChangesNothing() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        expect(202, cancel(asha, orderId));

        expect(202, supportCancel(orderId, "{\"reason_code\": \"CUSTOMER_REQUEST\"}"));

        assertThat(auditEntries()).as("nothing was requested").isZero();
        deliver();
        assertThat(reason(orderId)).isEqualTo("CUSTOMER");
    }

    private HttpResponse<String> supportCancel(UUID orderId, String body) {
        return call("POST", "/v1/support/orders/" + orderId + "/cancel", support, body, "Idempotency-Key", newKey());
    }

    private UUID customerOf(UUID orderId) {
        return jdbc.sql("SELECT customer_id FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single();
    }

    private int auditEntries() {
        return jdbc.sql("SELECT count(*) FROM platform.audit_log WHERE action = 'ordering.order.cancel-requested'")
                .query(Integer.class)
                .single();
    }
}
