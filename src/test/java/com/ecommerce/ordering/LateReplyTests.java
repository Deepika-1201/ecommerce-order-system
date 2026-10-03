package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.inventory.StockMessages.ReservationCommitted;
import com.ecommerce.inventory.StockMessages.StockReserved;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.ReserveCoupon;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Duplicated, late and reordered messages through the outbox (LLD §6.5): each changes nothing, or undoes what a late
 * reply did elsewhere.
 */
class LateReplyTests extends OrderingTest {

    @Test
    void duplicatesOfEveryReplyChangeNothingMidway() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = awaitingPayment(sku, 1, "WELCOME");
        JsonNode before = order(asha, orderId);
        UUID paymentId = paymentIdOf(orderId);

        publish(new StockReserved(orderId, reservationIdOf(orderId), Instant.now()), "reservation", orderId);
        publish(new CouponReserved(orderId), "coupon_redemption", orderId);
        publish(new PaymentCreated(orderId, paymentId, "https://elsewhere.invalid/" + paymentId), "payment", orderId);
        deliver();

        assertThat(order(asha, orderId)).isEqualTo(before);
        assertThat(pending(orderId)).isEmpty();
        assertStock(sku, 5, 1);
        assertCoupon(couponId, 1, 0);
    }

    @Test
    void duplicatesOfEveryReplyChangeNothingAtTheEnd() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("WELCOME", "");
        UUID orderId = confirmed(sku, 2, "WELCOME");
        shipments.handOver(orderId);
        deliver();
        shipments.deliver(orderId);
        deliver();
        long version = version(orderId);
        UUID paymentId = paymentIdOf(orderId);

        publish(new StockReserved(orderId, reservationIdOf(orderId), Instant.now()), "reservation", orderId);
        publish(new CouponReserved(orderId), "coupon_redemption", orderId);
        publish(new PaymentCreated(orderId, paymentId, "https://elsewhere.invalid/" + paymentId), "payment", orderId);
        publish(new PaymentSucceeded(orderId, paymentId, grandTotal(orderId)), "payment", orderId);
        publish(new ReservationCommitted(orderId), "reservation", orderId);
        publish(new ShipmentHandedOver(orderId), "shipment", orderId);
        publish(new ShipmentDelivered(orderId), "shipment", orderId);
        deliver();

        assertThat(status(orderId)).isEqualTo("DELIVERED");
        assertThat(version(orderId)).isEqualTo(version);
        assertThat(pending(orderId)).isEmpty();
        assertThat(refunds(orderId)).isEmpty();
        assertStock(sku, 3, 0);
        assertCoupon(couponId, 0, 1);
    }

    @Test
    void aSuccessThatOvertakesThePaymentsCreationIsActedOn() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        deliverExcept(PaymentCreated.class);
        assertThat(step(orderId)).isEqualTo("CREATING_PAYMENT");

        payments.succeed(orderId);
        deliverExcept(PaymentCreated.class);

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        long version = version(orderId);
        deliver();
        JsonNode order = order(asha, orderId);
        assertThat(order.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(order.has("checkout_url")).isFalse();
        assertThat(version(orderId)).as("the late PaymentCreated changed nothing").isEqualTo(version);
        assertThat(reservation(orderId)).isEqualTo("COMMITTED");
    }

    @Test
    void aFailureThatOvertakesThePaymentsCreationCancelsTheOrder() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = placeOrder(asha, sku, 1, null);
        deliverExcept(PaymentCreated.class);

        payments.fail(orderId);
        deliverExcept(PaymentCreated.class);
        deliver();

        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(reason(orderId)).isEqualTo("PAYMENT_FAILED");
        assertStock(sku, 5, 0);
    }

    @Test
    void aDeliveryThatOvertakesTheHandoverIsBoth() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = confirmed(sku, 2, null);
        shipments.handOver(orderId);
        shipments.deliver(orderId);

        deliverExcept(ShipmentHandedOver.class);

        assertThat(status(orderId)).isEqualTo("DELIVERED");
        assertThat(reservation(orderId)).isEqualTo("FULFILLED");
        long version = version(orderId);
        deliver();
        assertThat(version(orderId)).isEqualTo(version);
        assertStock(sku, 3, 0);
    }

    @Test
    void aCouponReservedLateForARejectedOrderIsReleased() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID couponId = coupon("SCARCE", "\"total_limit\": 1");
        UUID ashasQuote = quote(asha, KARNATAKA, "SCARCE", sku, 1);
        UUID ravisOrder = placeOrder(ravi, sku, 1, "SCARCE");
        deliver();
        UUID ashasOrder = id(expect(202, place(asha, ashasQuote, address(asha, KARNATAKA), newKey())));
        deliver();
        assertThat(reason(ashasOrder)).isEqualTo("COUPON_UNAVAILABLE");
        expect(202, cancel(ravi, ravisOrder));
        deliver();
        assertCoupon(couponId, 0, 0);

        // The command the sweep sent again before the rejection arrives now, and finds the coupon free.
        publish(new ReserveCoupon(ashasOrder, couponId, customerOf(ashasOrder)), "order", ashasOrder);
        deliver();

        assertThat(status(ashasOrder)).isEqualTo("REJECTED");
        assertThat(reason(ashasOrder)).isEqualTo("COUPON_UNAVAILABLE");
        assertThat(redemption(ashasOrder)).isEqualTo("RELEASED");
        assertCoupon(couponId, 0, 0);
    }

    @Test
    void aPaymentThatSucceedsAfterTheOrderWasCancelledIsRefunded() {
        String sku = product("Steel bottle", 59_900, 5);
        UUID orderId = awaitingPayment(sku, 1);
        payments.expire(orderId);
        deliver();
        assertThat(reason(orderId)).isEqualTo("PAYMENT_EXPIRED");

        payments.succeed(orderId);
        deliverExcept(RefundPayment.class);

        JsonNode refunding = order(asha, orderId);
        assertThat(refunding.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(refunding.get("reason").asString()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(refunding.get("refund").get("status").asString()).isEqualTo("REQUESTED");
        deliver();
        assertThat(order(asha, orderId).get("refund").get("status").asString()).isEqualTo("INITIATED");
        assertThat(refunds(orderId)).containsExactly(entry("LATE_SUCCESS", grandTotal(orderId)));
        assertStock(sku, 5, 0);
    }

    private UUID paymentIdOf(UUID orderId) {
        return jdbc.sql("SELECT payment_id FROM payments.simulated_payments WHERE order_id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single();
    }

    private UUID reservationIdOf(UUID orderId) {
        return jdbc.sql("SELECT id FROM inventory.reservations WHERE order_id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single();
    }

    private UUID customerOf(UUID orderId) {
        return jdbc.sql("SELECT customer_id FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single();
    }
}
