package com.ecommerce.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.fulfillment.ShipmentMessages.CancelShipment;
import com.ecommerce.fulfillment.ShipmentMessages.CreateShipment;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.inventory.StockMessages.CommitReservation;
import com.ecommerce.inventory.StockMessages.FulfillReservation;
import com.ecommerce.inventory.StockMessages.ReleaseReservation;
import com.ecommerce.inventory.StockMessages.ReservationCommitted;
import com.ecommerce.inventory.StockMessages.ReservationLost;
import com.ecommerce.inventory.StockMessages.ReserveStock;
import com.ecommerce.inventory.StockMessages.RestockReturn;
import com.ecommerce.inventory.StockMessages.StockReservationFailed;
import com.ecommerce.inventory.StockMessages.StockReserved;
import com.ecommerce.inventory.StockReservations;
import com.ecommerce.payments.PaymentMessages.CancelPayment;
import com.ecommerce.payments.PaymentMessages.CheckPayment;
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
import com.ecommerce.platform.tasks.DueTasks;
import com.ecommerce.pricing.CouponMessages.CommitCoupon;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.CouponUnavailable;
import com.ecommerce.pricing.CouponMessages.ReleaseCoupon;
import com.ecommerce.pricing.CouponMessages.ReserveCoupon;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The participants as the saga sees them (LLD §6.2, §6.8, §7.5): each command, sent through the outbox, is answered as
 * the contract says, and a repeat is answered the same. Replies stay in the outbox, where these tests read them;
 * Payments' answers come from its tasks, against the fake gateway.
 */
class ParticipantTests extends OrderingTest {

    private static final Duration HOLD = Duration.ofMinutes(50);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Class<?>[] REPLIES = {StockReserved.class, StockReservationFailed.class,
        ReservationCommitted.class, ReservationLost.class, CouponReserved.class, CouponUnavailable.class,
        PaymentCreated.class, PaymentCreationFailed.class, PaymentCancelled.class, PaymentCancelRefused.class,
        PaymentPending.class, RefundInitiated.class, RefundSucceeded.class, RefundFailed.class, PaymentSucceeded.class,
        PaymentFailed.class, PaymentExpired.class, ShipmentCancelled.class, ShipmentCancelRefused.class,
        ShipmentHandedOver.class, ShipmentDelivered.class, ShipmentReturnInitiated.class,
        ShipmentReturnedToOrigin.class};

    private final UUID orderId = UUID.randomUUID();

    @Test
    void reservingStockAnswersWithTheHoldAndARepeatWithTheSameHold() {
        String sku = product("Steel bottle", 59_900, 5);
        ReserveStock reserve = new ReserveStock(orderId, List.of(new StockReservations.Line(sku, 2)), HOLD);

        command(reserve);
        command(reserve);

        List<Reply> replies = replies();
        assertThat(replies).extracting(Reply::type)
                .containsExactly("inventory.stock-reserved", "inventory.stock-reserved");
        assertThat(replies.get(1).data()).isEqualTo(replies.get(0).data());
        assertThat(replies.get(0).aggregateType()).isEqualTo("reservation");
        assertThat(replies.get(0).sequence()).as("the reservation's version").isOne();
        JsonNode held = replies.get(0).data();
        assertThat(held.get("reservation_id").asString())
                .isEqualTo(jdbc.sql("SELECT id::text FROM inventory.reservations WHERE order_id = :id")
                        .param("id", orderId).query(String.class).single());
        assertThat(Duration.between(Instant.now(), Instant.parse(held.get("expires_at").asString())))
                .isBetween(HOLD.minusMinutes(1), HOLD);
        assertStock(sku, 5, 2);
    }

    @Test
    void reservingTooMuchStockAnswersWithTheSkuAndWhatWasAvailable() {
        String sku = product("Steel bottle", 59_900, 1);

        command(new ReserveStock(orderId, List.of(new StockReservations.Line(sku, 2)), HOLD));

        Reply reply = replies().getFirst();
        assertThat(reply.type()).isEqualTo("inventory.stock-reservation-failed");
        assertThat(reply.data().get("sku").asString()).isEqualTo(sku);
        assertThat(reply.data().get("available").asLong()).isOne();
    }

    @Test
    void committingAnswersCommittedOrLost() {
        String sku = product("Steel bottle", 59_900, 2);
        UUID late = UUID.randomUUID();
        command(new ReserveStock(late, List.of(new StockReservations.Line(sku, 2)), HOLD), late);
        jdbc.sql("UPDATE inventory.reservations SET expires_at = :past WHERE order_id = :id")
                .param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .param("id", late)
                .update();
        DueTasks.runRecurring(context, "inventory.expire-holds");
        command(new ReserveStock(orderId, List.of(new StockReservations.Line(sku, 2)), HOLD));

        command(new CommitReservation(orderId));
        command(new CommitReservation(late), late);

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("inventory.stock-reserved", "inventory.reservation-committed");
        assertThat(replies().get(1).sequence()).as("the reservation's version").isEqualTo(2);
        assertThat(replies(late)).extracting(Reply::type)
                .containsExactly("inventory.stock-reserved", "inventory.reservation-lost");
        assertStock(sku, 2, 2);
    }

    @Test
    void releasingFulfillingAndRestockingAreNotAnswered() {
        String sku = product("Steel bottle", 59_900, 5);
        command(new ReserveStock(orderId, List.of(new StockReservations.Line(sku, 2)), HOLD));
        command(new CommitReservation(orderId));

        command(new FulfillReservation(orderId));
        assertStock(sku, 3, 0);
        command(new RestockReturn(orderId));
        assertStock(sku, 5, 0);
        UUID released = UUID.randomUUID();
        command(new ReserveStock(released, List.of(new StockReservations.Line(sku, 1)), HOLD), released);
        command(new ReleaseReservation(released), released);
        assertStock(sku, 5, 0);

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("inventory.stock-reserved", "inventory.reservation-committed");
        assertThat(replies(released)).extracting(Reply::type).containsExactly("inventory.stock-reserved");
    }

    @Test
    void reservingACouponAnswersReservedOnceOrUnavailable() {
        UUID couponId = coupon("SCARCE", "\"total_limit\": 1");
        UUID customerId = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        command(new ReserveCoupon(orderId, couponId, customerId));
        command(new ReserveCoupon(orderId, couponId, customerId));
        command(new ReserveCoupon(other, couponId, UUID.randomUUID()), other);

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("pricing.coupon-reserved", "pricing.coupon-reserved");
        assertThat(replies().getFirst().aggregateType()).isEqualTo("coupon_redemption");
        assertThat(replies().getFirst().sequence()).isZero();
        Reply unavailable = replies(other).getFirst();
        assertThat(unavailable.type()).isEqualTo("pricing.coupon-unavailable");
        assertThat(unavailable.data().get("reason").asString()).isEqualTo("EXHAUSTED");
        assertCoupon(couponId, 1, 0);
        command(new CommitCoupon(orderId));
        assertCoupon(couponId, 0, 1);
        command(new ReleaseCoupon(orderId));
        assertCoupon(couponId, 0, 0);
        assertThat(replies()).hasSize(2);
    }

    @Test
    void creatingAPaymentAnswersWithItsCheckoutPageOncePerOrder() {
        command(new CreatePayment(orderId, CUSTOMER, 123_400, Instant.now().plus(Duration.ofMinutes(15))));
        command(new CreatePayment(orderId, CUSTOMER, 123_400, Instant.now().plus(Duration.ofMinutes(15))));

        List<Reply> replies = replies();
        assertThat(replies).extracting(Reply::type)
                .containsExactly("payments.payment-created", "payments.payment-created");
        assertThat(replies.get(1).data()).isEqualTo(replies.get(0).data());
        assertThat(replies.getFirst().data().get("payment_id").asString())
                .isEqualTo(column("SELECT id::text FROM payments.payment_records WHERE order_id = :id"));
        assertThat(replies.getFirst().data().get("checkout_url").asString())
                .startsWith("https://fake-gateway.invalid/checkout/cs_");
        assertThat(replies.getFirst().aggregateType()).isEqualTo("payment");
        assertThat(payment(orderId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    void aPaymentRefusedBeforeItsCreationIsNeverCreated() {
        payments.refuseCreation(orderId);
        payments.refuseCreation(orderId);

        command(new CreatePayment(orderId, CUSTOMER, 123_400, Instant.now().plus(Duration.ofMinutes(15))));
        command(new CreatePayment(orderId, CUSTOMER, 123_400, Instant.now().plus(Duration.ofMinutes(15))));

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("payments.payment-creation-failed", "payments.payment-creation-failed");
        assertThat(payment(orderId)).isEqualTo("CREATION_FAILED");
        UUID created = createdPayment();
        assertThatThrownBy(() -> payments.refuseCreation(created)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.succeed(orderId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingAPaymentAnswersAsItsStatusAllows() {
        UUID unpaid = createdPayment();
        UUID paying = createdPayment();
        payments.startAttempt(paying);
        UUID paid = createdPayment();
        payments.succeed(paid);
        UUID failed = createdPayment();
        payments.fail(failed);
        UUID expired = createdPayment();
        payments.expire(expired);

        for (UUID order : List.of(unpaid, unpaid, paying, paid, failed, expired)) {
            command(new CancelPayment(order), order);
        }

        assertThat(replies(unpaid)).extracting(Reply::type).containsExactly("payments.payment-created",
                "payments.payment-cancelled", "payments.payment-cancelled");
        assertThat(payment(unpaid)).isEqualTo("CANCELLED");
        assertThat(lastReply(paying)).as("an attempt in flight").isEqualTo("payments.payment-cancel-refused");
        assertThat(lastReply(paid)).as("a final payment answers with its outcome")
                .isEqualTo("payments.payment-succeeded");
        assertThat(lastReply(failed)).isEqualTo("payments.payment-failed");
        assertThat(lastReply(expired)).isEqualTo("payments.payment-expired");
        assertThat(payment(paying)).isEqualTo("PROCESSING");
        assertThat(payment(paid)).isEqualTo("SUCCEEDED");
    }

    @Test
    void checkingAPaymentAnswersPendingOrItsOutcome() {
        UUID payable = createdPayment();
        UUID paying = createdPayment();
        payments.startAttempt(paying);
        UUID paid = createdPayment();
        payments.succeed(paid);
        UUID expired = createdPayment();
        payments.expire(expired);

        for (UUID order : List.of(payable, paying, paid, expired)) {
            command(new CheckPayment(order), order);
        }

        assertThat(lastReply(payable)).isEqualTo("payments.payment-pending");
        assertThat(lastReply(paying)).as("an attempt in flight").isEqualTo("payments.payment-pending");
        Reply succeeded = replies(paid).getLast();
        assertThat(succeeded.type()).isEqualTo("payments.payment-succeeded");
        assertThat(succeeded.data().get("amount_paise").asLong()).isEqualTo(123_400);
        assertThat(lastReply(expired)).isEqualTo("payments.payment-expired");
    }

    @Test
    void aCheckFindsTheOutcomeOfAWebhookThatNeverCame() {
        UUID paid = createdPayment();
        payments.succeed(paid);
        jdbc.sql("DELETE FROM platform.scheduled_tasks WHERE type = 'payments.apply-gateway-event'").update();

        command(new CheckPayment(paid), paid);

        assertThat(replies(paid)).extracting(Reply::type)
                .containsExactly("payments.payment-created", "payments.payment-succeeded");
        assertThat(payment(paid)).isEqualTo("SUCCEEDED");
    }

    @Test
    void refundsAreOnePerOrderAndReason() {
        UUID paid = paidPayment();

        command(new RefundPayment(paid, 100_000, RefundReason.ORDER_CANCELLED), paid);
        command(new RefundPayment(paid, 100_000, RefundReason.ORDER_CANCELLED), paid);
        command(new RefundPayment(paid, 23_400, RefundReason.LATE_SUCCESS), paid);

        List<JsonNode> refunds = replies(paid).stream()
                .filter(reply -> reply.type().equals("payments.refund-initiated"))
                .map(Reply::data)
                .toList();
        assertThat(refunds).hasSize(3);
        assertThat(refunds.get(1)).isEqualTo(refunds.get(0));
        assertThat(refunds.get(2).get("refund_id")).isNotEqualTo(refunds.get(0).get("refund_id"));
        assertThat(refunds.get(0).get("reason").asString()).isEqualTo("ORDER_CANCELLED");
        assertThat(refunds.get(0).get("amount_paise").asLong()).isEqualTo(100_000);
        assertThat(refunds(paid)).containsOnlyKeys("ORDER_CANCELLED", "LATE_SUCCESS");
    }

    @Test
    void aRefundEndsByTheGatewaysEvent() {
        UUID refunded = paidPayment();
        command(new RefundPayment(refunded, 123_400, RefundReason.ORDER_CANCELLED), refunded);
        UUID unlucky = paidPayment();
        command(new RefundPayment(unlucky, 123_400, RefundReason.RETURNED_TO_ORIGIN), unlucky);

        payments.succeedRefund(refunded);
        payments.failRefund(unlucky);
        deliverExcept(REPLIES);

        Reply succeeded = replies(refunded).getLast();
        assertThat(succeeded.type()).isEqualTo("payments.refund-succeeded");
        assertThat(succeeded.data().get("reason").asString()).isEqualTo("ORDER_CANCELLED");
        assertThat(succeeded.data().get("amount_paise").asLong()).isEqualTo(123_400);
        assertThat(lastReply(unlucky)).isEqualTo("payments.refund-failed");
    }

    @Test
    void onlyASuccessfulPaymentIsRefunded() {
        UUID unpaid = createdPayment();

        assertThatThrownBy(() -> command(new RefundPayment(unpaid, 123_400, RefundReason.ORDER_CANCELLED), unpaid))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cannot be refunded: its payment is REQUIRES_PAYMENT");
        assertThat(refunds(unpaid)).isEmpty();
    }

    @Test
    void aCancelOrACheckBeforeThePaymentExistsFailsItsDeliveryToBeTriedAgain() {
        UUID unchecked = UUID.randomUUID();

        assertThatThrownBy(() -> command(new CancelPayment(orderId)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Order " + orderId + " has no payment");
        assertThatThrownBy(() -> command(new CheckPayment(unchecked), unchecked))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Order " + unchecked + " has no payment");
    }

    @Test
    void theFakeGatewayRefusesWhatAPaymentsStatusDoesNotAllow() {
        UUID cancelled = createdPayment();
        command(new CancelPayment(cancelled), cancelled);
        UUID paid = createdPayment();
        payments.succeed(paid);
        UUID paying = createdPayment();
        payments.startAttempt(paying);

        assertThatThrownBy(() -> payments.fail(cancelled)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.fail(paid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.expire(paid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.startAttempt(paying)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.failAttempt(paid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.succeedRefund(paid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.succeed(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(payment(paid)).isEqualTo("SUCCEEDED");
    }

    @Test
    void aShipmentIsBookedAtOnceAndCancelledUntilItIsHandedOver() {
        command(new CreateShipment(orderId, UUID.randomUUID()));
        assertThat(shipment(orderId)).isEqualTo("BOOKED");
        assertThat(replies()).isEmpty();

        command(new CancelShipment(orderId));
        command(new CancelShipment(orderId));

        assertThat(replies()).extracting(Reply::type)
                .containsExactly("fulfillment.shipment-cancelled", "fulfillment.shipment-cancelled");
        assertThat(replies().getFirst().aggregateType()).isEqualTo("shipment");
        assertThat(shipment(orderId)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> shipments.handOver(orderId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCancellationThatArrivesBeforeTheShipmentIsCreatedStands() {
        command(new CancelShipment(orderId));
        command(new CreateShipment(orderId, UUID.randomUUID()));

        assertThat(replies()).extracting(Reply::type).containsExactly("fulfillment.shipment-cancelled");
        assertThat(shipment(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void aHandedOverShipmentCannotBeCancelledAndMovesOnAsTheParcelDoes() {
        command(new CreateShipment(orderId, UUID.randomUUID()));
        shipments.handOver(orderId);
        command(new CancelShipment(orderId));
        shipments.startReturn(orderId);
        shipments.completeReturn(orderId);
        UUID delivered = UUID.randomUUID();
        command(new CreateShipment(delivered, UUID.randomUUID()), delivered);
        shipments.handOver(delivered);
        shipments.deliver(delivered);

        assertThat(replies()).extracting(Reply::type).containsExactly("fulfillment.shipment-handed-over",
                "fulfillment.shipment-cancel-refused", "fulfillment.shipment-return-initiated",
                "fulfillment.shipment-returned-to-origin");
        assertThat(shipment(orderId)).isEqualTo("RETURNED");
        assertThat(shipment(delivered)).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> shipments.startReturn(delivered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shipments.deliver(orderId)).isInstanceOf(IllegalStateException.class);
    }

    /** A payment of ₹1,234 for a new order, payable for 15 minutes; the order's id. */
    private UUID createdPayment() {
        UUID order = UUID.randomUUID();
        command(new CreatePayment(order, CUSTOMER, 123_400, Instant.now().plus(Duration.ofMinutes(15))), order);
        return order;
    }

    /** A payment that succeeded, with the gateway's event applied, as before the saga asks for a refund. */
    private UUID paidPayment() {
        UUID order = createdPayment();
        payments.succeed(order);
        deliverExcept(REPLIES);
        return order;
    }

    private String column(String sql) {
        return jdbc.sql(sql).param("id", orderId).query(String.class).single();
    }

    private void command(Object command) {
        command(command, orderId);
    }

    /** Sends a saga command for the order through the outbox and delivers it, leaving every reply in the outbox. */
    private void command(Object command, UUID order) {
        publish(command, "order", order);
        deliverExcept(REPLIES);
    }

    private List<Reply> replies() {
        return replies(orderId);
    }

    /** The replies and events for the order's process, oldest first. */
    private List<Reply> replies(UUID order) {
        return jdbc.sql("""
                        SELECT type, aggregate_type, sequence, envelope FROM platform.outbox
                        WHERE aggregate_id = :order AND delivered_at IS NULL AND destination LIKE 'handler:ordering.%'
                        ORDER BY id
                        """)
                .param("order", order.toString())
                .query((row, n) -> new Reply(row.getString("type"), row.getString("aggregate_type"),
                        row.getLong("sequence"), JSON.readTree(row.getString("envelope")).get("data")))
                .list();
    }

    private String lastReply(UUID order) {
        return replies(order).getLast().type();
    }

    private record Reply(String type, String aggregateType, long sequence, JsonNode data) {
    }
}
