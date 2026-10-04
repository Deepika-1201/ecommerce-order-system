package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.platform.tasks.DueTasks;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Orders from placement to each of their ends, through the outbox, with the real Inventory and Pricing (LLD §6.5).
 * Stock, coupon counters and refunds are checked at every end.
 */
class OrderFlowTests extends OrderingTest {

    @Test
    void anOrderIsPaidShippedAndDelivered() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "\"total_limit\": 10");

        UUID orderId = placeOrder(asha, sku, 2, "WELCOME");
        deliver();

        JsonNode awaiting = order(asha, orderId);
        assertThat(awaiting.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(awaiting.get("checkout_url").asString()).startsWith("https://fake-gateway.invalid/checkout/cs_");
        assertStock(sku, 5, 2);
        assertCoupon(couponId, 1, 0);
        assertThat(payment(orderId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        Instant holdExpiry = instant("SELECT expires_at FROM inventory.reservations WHERE order_id = :id", orderId);
        assertThat(Duration.between(Instant.now(), holdExpiry)).as("window, grace and margin")
                .isBetween(Duration.ofMinutes(49), Duration.ofMinutes(50));
        assertThat(instant("SELECT expires_at FROM payments.payment_records WHERE order_id = :id", orderId))
                .as("the payment window ends before the grace and the margin")
                .isEqualTo(holdExpiry.minus(Duration.ofMinutes(35)));
        assertThat(jdbc.sql("SELECT DISTINCT envelope::jsonb ->> 'correlation_id' FROM platform.outbox "
                        + "WHERE aggregate_id = :id")
                .param("id", orderId.toString())
                .query(String.class)
                .list()).as("one flow").containsExactly(orderId.toString());
        assertThat(envelopeField("pricing.reserve-coupon", "causation_id", orderId))
                .as("caused by the reply before it")
                .isEqualTo(envelopeField("inventory.stock-reserved", "message_id", orderId));

        payments.succeed(orderId);
        deliver();

        JsonNode confirmed = order(asha, orderId);
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.has("checkout_url")).isFalse();
        assertThat(reservation(orderId)).isEqualTo("COMMITTED");
        assertCoupon(couponId, 0, 1);
        assertThat(shipment(orderId)).isEqualTo("BOOKED");

        shipments.handOver(orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("SHIPPED");
        assertThat(reservation(orderId)).isEqualTo("FULFILLED");
        assertStock(sku, 3, 0);

        shipments.deliver(orderId);
        deliver();

        JsonNode delivered = order(asha, orderId);
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.has("reason")).isFalse();
        assertThat(delivered.has("refund")).isFalse();
        JsonNode shipment = delivered.get("shipment");
        assertThat(shipment.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(shipment.get("carrier").asString()).isEqualTo("Simulated Carrier");
        assertThat(shipment.get("awb").asString()).matches("SIM[0-9]{10}");
        assertThat(shipment.get("tracking").valueStream().map(step -> step.get("status").asString()))
                .as("newest first")
                .containsExactly("DELIVERED", "OUT_FOR_DELIVERY", "IN_TRANSIT", "HANDED_OVER", "PACKED");
        assertThat(step(orderId)).isEqualTo("DONE");
        assertThat(refunds(orderId)).isEmpty();
        assertCoupon(couponId, 0, 1);
        assertThat(pending(orderId)).isEmpty();
    }

    @Test
    void anOrderReturnedToOriginIsRestockedAndRefunded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 2, null);
        shipments.handOver(orderId);
        deliver();
        shipments.startReturn(orderId);
        deliver();
        assertThat(status(orderId)).isEqualTo("DELIVERY_FAILED");

        shipments.completeReturn(orderId);
        deliverExcept(RefundInitiated.class);

        JsonNode returned = order(asha, orderId);
        assertThat(returned.get("status").asString()).isEqualTo("RETURNED_TO_ORIGIN");
        assertThat(returned.get("refund").get("status").asString()).as("asked for").isEqualTo("REQUESTED");
        assertThat(returned.get("refund").get("amount_paise").asLong()).isEqualTo(grandTotal(orderId));
        assertThat(reservation(orderId)).isEqualTo("RETURNED");
        assertStock(sku, 5, 0);

        deliver();

        assertThat(order(asha, orderId).get("refund").get("status").asString()).isEqualTo("INITIATED");
        assertThat(step(orderId)).isEqualTo("DONE");
        assertThat(refunds(orderId)).containsExactly(entry("RETURNED_TO_ORIGIN", grandTotal(orderId)));
    }

    @Test
    void aParcelTheCarrierCannotDeliverComesBackRestockedAndRefunded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID quoteId = quote(asha, KARNATAKA, null, sku, 2);
        UUID orderId = id(expect(202, place(asha, quoteId, address(asha, KARNATAKA, "560005"), newKey())));
        deliver();
        payments.succeed(orderId);
        deliver();
        shipments.handOver(orderId);
        deliver();

        shipments.followScenario(orderId);
        deliver();
        payments.succeedRefund(orderId);
        deliver();

        JsonNode returned = order(asha, orderId);
        assertThat(returned.get("status").asString()).isEqualTo("RETURNED_TO_ORIGIN");
        assertThat(returned.get("shipment").get("status").asString()).isEqualTo("RTO_DELIVERED");
        assertThat(returned.get("refund").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(returned.get("refund").get("amount_paise").asLong()).isEqualTo(grandTotal(orderId));
        assertThat(reservation(orderId)).isEqualTo("RETURNED");
        assertStock(sku, 5, 0);
        assertThat(step(orderId)).isEqualTo("DONE");
    }

    @Test
    void anOrderShortOfStockIsRejectedNamingTheSkuAndHoldsNothing() {
        String sku = product("Steel bottle", 59_900, 1);

        UUID orderId = placeOrder(asha, sku, 2, null);
        deliver();

        JsonNode order = order(asha, orderId);
        assertThat(order.get("status").asString()).isEqualTo("REJECTED");
        assertThat(order.get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(order.get("unavailable_sku").asString()).isEqualTo(sku);
        assertStock(sku, 1, 0);
        assertThat(payment(orderId)).isNull();
        assertThat(pending(orderId)).isEmpty();
    }

    @Test
    void anUnavailableCouponRejectsTheOrderAndReleasesTheStock() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID quoteId = quote(asha, KARNATAKA, "WELCOME", sku, 1);
        jdbc.sql("UPDATE pricing.coupons SET active = false WHERE id = :id").param("id", couponId).update();

        UUID orderId = id(expect(202, place(asha, quoteId, address(asha, KARNATAKA), newKey())));
        deliver();

        assertThat(status(orderId)).isEqualTo("REJECTED");
        assertThat(reason(orderId)).isEqualTo("COUPON_UNAVAILABLE");
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertCoupon(couponId, 0, 0);
        assertThat(payment(orderId)).isNull();
    }

    @Test
    void aPaymentThatCannotBeCreatedRejectsTheOrderAndReleasesEverything() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = placeOrder(asha, sku, 1, "WELCOME");
        payments.refuseCreation(orderId);

        deliver();

        assertThat(status(orderId)).isEqualTo("REJECTED");
        assertThat(reason(orderId)).isEqualTo("PAYMENTS_UNAVAILABLE");
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertThat(redemption(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertCoupon(couponId, 0, 0);
    }

    @Test
    void aFailedPaymentCancelsTheOrderAndReleasesEverything() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = awaitingPayment(sku, 1, "WELCOME");

        payments.fail(orderId);
        deliver();

        JsonNode order = order(asha, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(order.get("reason").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(order.has("checkout_url")).isFalse();
        assertThat(order.has("refund")).isFalse();
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertThat(redemption(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertCoupon(couponId, 0, 0);
    }

    @Test
    void anExpiredPaymentCancelsTheOrderAndReleasesTheStock() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 3);

        payments.expire(orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(reservation(orderId)).isEqualTo("RELEASED");
        assertStock(sku, 5, 0);
        assertThat(refunds(orderId)).isEmpty();
    }

    @Test
    void aHoldLostAfterThePaymentIsRefundedAndReleasesTheCoupon() {
        String sku = product("Steel bottle", 59_900, 2);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = awaitingPayment(sku, 2, "WELCOME");
        jdbc.sql("UPDATE inventory.reservations SET expires_at = :past WHERE order_id = :id")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .param("id", orderId)
                .update();
        DueTasks.runRecurring(context, "inventory.expire-holds");
        UUID ravisOrder = placeOrder(ravi, sku, 2, null);
        deliver();
        assertThat(status(ravisOrder)).as("took the units back").isEqualTo("AWAITING_PAYMENT");

        payments.succeed(orderId);
        deliver();

        JsonNode lost = order(asha, orderId);
        assertThat(lost.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(lost.get("reason").asString()).isEqualTo("STOCK_LOST_AFTER_PAYMENT");
        assertThat(lost.get("refund").get("status").asString()).isEqualTo("INITIATED");
        assertThat(lost.get("refund").get("amount_paise").asLong()).isEqualTo(grandTotal(orderId));
        assertThat(refunds(orderId)).containsExactly(entry("STOCK_LOST_AFTER_PAYMENT", grandTotal(orderId)));
        assertThat(redemption(orderId)).isEqualTo("RELEASED");
        assertCoupon(couponId, 0, 0);
        assertStock(sku, 2, 2);
    }

    private Instant instant(String sql, UUID orderId) {
        return jdbc.sql(sql)
                .param("id", orderId)
                .query((row, n) -> row.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private String envelopeField(String type, String field, UUID orderId) {
        return jdbc.sql("SELECT envelope::jsonb ->> :field FROM platform.outbox WHERE type = :type AND aggregate_id = :id")
                .param("field", field)
                .param("type", type)
                .param("id", orderId.toString())
                .query(String.class)
                .single();
    }
}
