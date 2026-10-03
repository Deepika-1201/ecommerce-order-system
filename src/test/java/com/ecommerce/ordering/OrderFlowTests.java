package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.platform.tasks.DueTasks;
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
        assertThat(awaiting.get("checkout_url").asString()).startsWith("https://checkout.simulator.invalid/pay/");
        assertStock(sku, 5, 2);
        assertCoupon(couponId, 1, 0);
        assertThat(payment(orderId)).isEqualTo("REQUIRES_PAYMENT");

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
}
