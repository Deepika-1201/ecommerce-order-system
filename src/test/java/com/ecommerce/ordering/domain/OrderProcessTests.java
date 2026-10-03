package com.ecommerce.ordering.domain;

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
import com.ecommerce.ordering.domain.OrderProcess.CancelOutcome;
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
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.payments.PaymentMessages.RefundPayment;
import com.ecommerce.payments.PaymentMessages.RefundReason;
import com.ecommerce.pricing.CouponMessages.CommitCoupon;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.CouponUnavailable;
import com.ecommerce.pricing.CouponMessages.ReleaseCoupon;
import com.ecommerce.pricing.CouponMessages.ReserveCoupon;
import com.ecommerce.pricing.CouponReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The order process's decisions, cell by cell (LLD §6.5-§6.7), without a database: each test brings a process to a
 * step through the messages that lead there, reloads it as the repository would, and applies one more input.
 */
class OrderProcessTests {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofMinutes(1));
    private static final OrderingProperties SETTINGS =
            new OrderingProperties(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(5));
    private static final Duration HOLD = Duration.ofMinutes(50);
    private static final Instant HOLD_EXPIRY = T0.plus(HOLD);
    private static final Instant PAYMENT_EXPIRY = T0.plus(Duration.ofMinutes(15));

    private static final UUID ORDER = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID COUPON = UUID.fromString("01900000-0000-7000-8000-000000000003");
    private static final UUID ADDRESS = UUID.fromString("01900000-0000-7000-8000-000000000004");
    private static final UUID RESERVATION = UUID.fromString("01900000-0000-7000-8000-000000000005");
    private static final UUID PAYMENT = UUID.fromString("01900000-0000-7000-8000-000000000006");
    private static final UUID REFUND = UUID.fromString("01900000-0000-7000-8000-000000000007");
    private static final String CHECKOUT = "https://checkout.simulator.invalid/pay/" + PAYMENT;
    private static final long TOTAL = 123_456;
    private static final List<StockReservations.Line> LINES =
            List.of(new StockReservations.Line("SKU-A", 2), new StockReservations.Line("SKU-B", 1));

    private static final StockReserved STOCK_RESERVED = new StockReserved(ORDER, RESERVATION, HOLD_EXPIRY);
    private static final StockReservationFailed STOCK_SHORT = new StockReservationFailed(ORDER, "SKU-B", 0);
    private static final CouponReserved COUPON_RESERVED = new CouponReserved(ORDER);
    private static final CouponUnavailable COUPON_UNAVAILABLE =
            new CouponUnavailable(ORDER, CouponReservation.Reason.EXHAUSTED);
    private static final PaymentCreated PAYMENT_CREATED = new PaymentCreated(ORDER, PAYMENT, CHECKOUT);
    private static final PaymentCreationFailed PAYMENT_CREATION_FAILED = new PaymentCreationFailed(ORDER);
    private static final PaymentSucceeded PAYMENT_SUCCEEDED = new PaymentSucceeded(ORDER, PAYMENT, TOTAL);
    private static final PaymentFailed PAYMENT_FAILED = new PaymentFailed(ORDER, PAYMENT);
    private static final PaymentExpired PAYMENT_EXPIRED = new PaymentExpired(ORDER, PAYMENT);
    private static final PaymentCancelled PAYMENT_CANCELLED = new PaymentCancelled(ORDER, PAYMENT);
    private static final PaymentCancelRefused CANCEL_REFUSED = new PaymentCancelRefused(ORDER, PAYMENT);
    private static final PaymentPending PAYMENT_PENDING = new PaymentPending(ORDER, PAYMENT);
    private static final ReservationCommitted COMMITTED = new ReservationCommitted(ORDER);
    private static final ReservationLost LOST = new ReservationLost(ORDER);
    private static final ShipmentHandedOver HANDED_OVER = new ShipmentHandedOver(ORDER);
    private static final ShipmentDelivered DELIVERED = new ShipmentDelivered(ORDER);
    private static final ShipmentReturnInitiated RETURN_INITIATED = new ShipmentReturnInitiated(ORDER);
    private static final ShipmentReturnedToOrigin RETURNED = new ShipmentReturnedToOrigin(ORDER);
    private static final ShipmentCancelled SHIPMENT_CANCELLED = new ShipmentCancelled(ORDER);
    private static final ShipmentCancelRefused SHIPMENT_CANCEL_REFUSED = new ShipmentCancelRefused(ORDER);

    private static final ReleaseReservation RELEASE_STOCK = new ReleaseReservation(ORDER);
    private static final ReleaseCoupon RELEASE_COUPON = new ReleaseCoupon(ORDER);
    private static final CreatePayment CREATE_PAYMENT = new CreatePayment(ORDER, TOTAL, PAYMENT_EXPIRY);

    // Builders: a process with a coupon unless a test says otherwise, driven at T0 and reloaded.

    private static Scenario order() {
        return new Scenario(COUPON);
    }

    private static Scenario orderWithoutCoupon() {
        return new Scenario(null);
    }

    private static Scenario awaitingPayment() {
        return order().then(STOCK_RESERVED, COUPON_RESERVED, PAYMENT_CREATED);
    }

    private static Scenario committing() {
        return awaitingPayment().then(PAYMENT_SUCCEEDED);
    }

    private static Scenario confirmed() {
        return committing().then(COMMITTED);
    }

    private static Scenario shipped() {
        return confirmed().then(HANDED_OVER);
    }

    /** A representative process in each step: with a coupon, paid when the step is after the payment. */
    private static OrderProcess inStep(Step step) {
        return (switch (step) {
            case RESERVING_STOCK -> order();
            case RESERVING_COUPON -> order().then(STOCK_RESERVED);
            case CREATING_PAYMENT -> order().then(STOCK_RESERVED, COUPON_RESERVED);
            case AWAITING_PAYMENT -> awaitingPayment();
            case COMMITTING_STOCK -> committing();
            case AWAITING_HANDOVER -> confirmed();
            case AWAITING_DELIVERY -> shipped();
            case AWAITING_RETURN -> shipped().then(RETURN_INITIATED);
            case CANCELLING_PAYMENT -> awaitingPayment().cancelled();
            case AWAITING_PAYMENT_OUTCOME -> awaitingPayment().cancelled().then(CANCEL_REFUSED);
            case CANCELLING_SHIPMENT -> confirmed().cancelled();
            case REFUNDING -> committing().then(LOST);
            case DONE -> shipped().then(DELIVERED);
        }).reloaded();
    }

    @Test
    void placementStartsByReservingTheStockForTheHold() {
        OrderProcess process = OrderProcess.start(facts(COUPON), SETTINGS, T0);

        assertThat(process.commands()).containsExactly(new ReserveStock(ORDER, LINES, HOLD));
        assertState(process, OrderStatus.PLACED, null, Step.RESERVING_STOCK, T0.plus(OrderProcess.STEP_TIMEOUT));
        assertThat(process.state().attempts()).isEqualTo(1);
        assertThat(process.sequence()).as("saved as version 1").isEqualTo(1);
    }

    @Nested
    class ReservingStock {

        @Test
        void reservedStockGoesOnToTheCouponWithTheHoldsExpiry() {
            OrderProcess process = decide(order(), STOCK_RESERVED);

            assertThat(process.commands()).containsExactly(new ReserveCoupon(ORDER, COUPON, CUSTOMER));
            assertState(process, OrderStatus.PLACED, null, Step.RESERVING_COUPON, NOW.plus(OrderProcess.STEP_TIMEOUT));
            assertThat(process.state().holdExpiresAt()).isEqualTo(HOLD_EXPIRY);
        }

        @Test
        void withoutACouponReservedStockGoesOnToThePaymentThatExpiresWithTheWindow() {
            OrderProcess process = decide(orderWithoutCoupon(), STOCK_RESERVED);

            assertThat(process.commands()).containsExactly(CREATE_PAYMENT);
            assertState(process, OrderStatus.PLACED, null, Step.CREATING_PAYMENT,
                    NOW.plus(OrderProcess.STEP_TIMEOUT));
        }

        @Test
        void aShortLineRejectsTheOrderNamingTheSku() {
            OrderProcess process = decide(order(), STOCK_SHORT);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.REJECTED, OrderReason.OUT_OF_STOCK, Step.DONE, null);
            assertThat(process.state().shortSku()).isEqualTo("SKU-B");
        }

        @Test
        void aCancellationReleasesTheReservedStock() {
            OrderProcess process = decide(order().cancelled(), STOCK_RESERVED);

            assertThat(process.commands()).containsExactly(RELEASE_STOCK);
            assertState(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, Step.DONE, null);
        }

        @Test
        void aCancelledOrderShortOfStockIsCancelledNotRejected() {
            OrderProcess process = decide(order().cancelled(), STOCK_SHORT);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, Step.DONE, null);
            assertThat(process.state().shortSku()).isNull();
        }
    }

    @Nested
    class ReservingCoupon {

        private final Scenario reserving = order().then(STOCK_RESERVED);

        @Test
        void aReservedCouponGoesOnToThePayment() {
            OrderProcess process = decide(reserving, COUPON_RESERVED);

            assertThat(process.commands()).containsExactly(CREATE_PAYMENT);
            assertState(process, OrderStatus.PLACED, null, Step.CREATING_PAYMENT,
                    NOW.plus(OrderProcess.STEP_TIMEOUT));
        }

        @Test
        void anUnavailableCouponReleasesTheStockAndRejectsTheOrder() {
            OrderProcess process = decide(reserving, COUPON_UNAVAILABLE);

            assertThat(process.commands()).containsExactly(RELEASE_STOCK);
            assertState(process, OrderStatus.REJECTED, OrderReason.COUPON_UNAVAILABLE, Step.DONE, null);
        }

        @Test
        void aCancellationReleasesTheStockAndTheReservedCoupon() {
            OrderProcess process = decide(reserving.cancelled(), COUPON_RESERVED);

            assertThat(process.commands()).containsExactly(RELEASE_STOCK, RELEASE_COUPON);
            assertState(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, Step.DONE, null);
        }

        @Test
        void aCancellationWithAnUnavailableCouponReleasesTheStock() {
            OrderProcess process = decide(reserving.cancelled(), COUPON_UNAVAILABLE);

            assertThat(process.commands()).containsExactly(RELEASE_STOCK);
            assertState(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, Step.DONE, null);
        }
    }

    @Nested
    class CreatingPayment {

        private final Scenario creating = order().then(STOCK_RESERVED, COUPON_RESERVED);

        @Test
        void aCreatedPaymentAwaitsTheCustomerUntilTheHoldExpires() {
            OrderProcess process = decide(creating, PAYMENT_CREATED);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.AWAITING_PAYMENT, null, Step.AWAITING_PAYMENT, HOLD_EXPIRY);
            assertThat(process.state().paymentId()).isEqualTo(PAYMENT);
            assertThat(process.state().checkoutUrl()).isEqualTo(CHECKOUT);
            assertThat(process.state().attempts()).isZero();
        }

        @Test
        void aFailedCreationReleasesEverythingAndRejectsTheOrder() {
            OrderProcess process = decide(creating, PAYMENT_CREATION_FAILED);

            assertThat(process.commands()).containsExactly(RELEASE_STOCK, RELEASE_COUPON);
            assertState(process, OrderStatus.REJECTED, OrderReason.PAYMENTS_UNAVAILABLE, Step.DONE, null);
        }

        @Test
        void aSuccessThatOvertookTheCreatedReplyCommitsTheStock() {
            OrderProcess process = decide(creating, PAYMENT_SUCCEEDED);

            assertThat(process.commands()).containsExactly(new CommitReservation(ORDER));
            assertState(process, OrderStatus.AWAITING_PAYMENT, null, Step.COMMITTING_STOCK,
                    NOW.plus(OrderProcess.STEP_TIMEOUT));
            assertThat(process.state().paymentId()).isEqualTo(PAYMENT);
            assertThat(process.state().paymentSucceeded()).isTrue();
            assertThat(process.state().checkoutUrl()).isNull();
        }

        @Test
        void aFailureThatOvertookTheCreatedReplyCancelsTheOrder() {
            assertCancelledWithRelease(decide(creating, PAYMENT_FAILED), OrderReason.PAYMENT_FAILED);
            assertCancelledWithRelease(decide(creating, PAYMENT_CANCELLED), OrderReason.PAYMENT_FAILED);
            assertCancelledWithRelease(decide(creating, PAYMENT_EXPIRED), OrderReason.PAYMENT_EXPIRED);
        }

        @Test
        void aCancellationCancelsTheCreatedPayment() {
            OrderProcess process = decide(creating.cancelled(), PAYMENT_CREATED);

            assertThat(process.commands()).containsExactly(new CancelPayment(ORDER));
            assertState(process, OrderStatus.CANCELLING, null, Step.CANCELLING_PAYMENT,
                    NOW.plus(OrderProcess.CANCEL_TIMEOUT));
            assertThat(process.state().paymentId()).isEqualTo(PAYMENT);
            assertThat(process.state().checkoutUrl()).isNull();
        }

        @Test
        void aCancellationWithAFailedCreationReleasesEverything() {
            assertCancelledWithRelease(decide(creating.cancelled(), PAYMENT_CREATION_FAILED), OrderReason.CUSTOMER);
        }

        @Test
        void aCancellationOvertakenByASuccessRefundsIt() {
            OrderProcess process = decide(creating.cancelled(), PAYMENT_SUCCEEDED);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, RefundReason.ORDER_CANCELLED,
                    RELEASE_STOCK, RELEASE_COUPON);
            assertThat(process.state().paymentSucceeded()).isTrue();
        }

        @Test
        void aCancellationOvertakenByAFailureReleasesEverything() {
            assertCancelledWithRelease(decide(creating.cancelled(), PAYMENT_FAILED), OrderReason.CUSTOMER);
        }
    }

    @Nested
    class AwaitingPayment {

        @Test
        void aSuccessCommitsTheStock() {
            OrderProcess process = decide(awaitingPayment(), PAYMENT_SUCCEEDED);

            assertThat(process.commands()).containsExactly(new CommitReservation(ORDER));
            assertState(process, OrderStatus.AWAITING_PAYMENT, null, Step.COMMITTING_STOCK,
                    NOW.plus(OrderProcess.STEP_TIMEOUT));
            assertThat(process.state().paymentSucceeded()).isTrue();
            assertThat(process.state().checkoutUrl()).as("nothing left to pay").isNull();
        }

        @Test
        void aFailedOrCancelledPaymentReleasesEverythingAndCancelsTheOrder() {
            assertCancelledWithRelease(decide(awaitingPayment(), PAYMENT_FAILED), OrderReason.PAYMENT_FAILED);
            assertCancelledWithRelease(decide(awaitingPayment(), PAYMENT_CANCELLED), OrderReason.PAYMENT_FAILED);
        }

        @Test
        void anExpiredPaymentReleasesEverythingAndCancelsTheOrder() {
            assertCancelledWithRelease(decide(awaitingPayment(), PAYMENT_EXPIRED), OrderReason.PAYMENT_EXPIRED);
        }

        @Test
        void aPendingPaymentIsCheckedAgainInFiveMinutes() {
            OrderProcess process = decide(awaitingPayment(), PAYMENT_PENDING);

            assertThat(process.changed()).isTrue();
            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.AWAITING_PAYMENT, null, Step.AWAITING_PAYMENT,
                    NOW.plus(OrderProcess.RECHECK_INTERVAL));
        }
    }

    @Nested
    class CommittingStock {

        @Test
        void aCommittedHoldConfirmsTheOrderCommitsTheCouponAndBooksTheShipment() {
            OrderProcess process = decide(committing(), COMMITTED);

            assertThat(process.commands())
                    .containsExactly(new CommitCoupon(ORDER), new CreateShipment(ORDER, ADDRESS));
            assertState(process, OrderStatus.CONFIRMED, null, Step.AWAITING_HANDOVER,
                    NOW.plus(OrderProcess.HANDOVER_TIMEOUT));
        }

        @Test
        void withoutACouponOnlyTheShipmentIsBooked() {
            Scenario committing = orderWithoutCoupon().then(STOCK_RESERVED, PAYMENT_CREATED, PAYMENT_SUCCEEDED);

            assertThat(decide(committing, COMMITTED).commands()).containsExactly(new CreateShipment(ORDER, ADDRESS));
        }

        @Test
        void aLostHoldIsRefundedAndReleasesTheCoupon() {
            OrderProcess process = decide(committing(), LOST);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.STOCK_LOST_AFTER_PAYMENT,
                    RefundReason.STOCK_LOST_AFTER_PAYMENT, RELEASE_COUPON);
        }

        @Test
        void aCancellationRefundsAndReleasesTheCommittedHold() {
            OrderProcess process = decide(committing().cancelled(), COMMITTED);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, RefundReason.ORDER_CANCELLED,
                    RELEASE_STOCK, RELEASE_COUPON);
        }

        @Test
        void aCancellationWithALostHoldRefundsAndReleasesTheCoupon() {
            OrderProcess process = decide(committing().cancelled(), LOST);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, RefundReason.ORDER_CANCELLED,
                    RELEASE_COUPON);
        }
    }

    @Nested
    class Shipping {

        @Test
        void theHandoverFulfilsTheHoldAndShipsTheOrder() {
            OrderProcess process = decide(confirmed(), HANDED_OVER);

            assertThat(process.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(process, OrderStatus.SHIPPED, null, Step.AWAITING_DELIVERY,
                    NOW.plus(OrderProcess.DELIVERY_TIMEOUT));
        }

        @Test
        void aDeliveryBeforeTheHandoverIsBothHandoverAndDelivery() {
            OrderProcess process = decide(confirmed(), DELIVERED);

            assertThat(process.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(process, OrderStatus.DELIVERED, null, Step.DONE, null);
        }

        @Test
        void aReturnBeforeTheHandoverWaitsForTheParcel() {
            OrderProcess process = decide(confirmed(), RETURN_INITIATED);

            assertThat(process.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(process, OrderStatus.DELIVERY_FAILED, null, Step.AWAITING_RETURN,
                    NOW.plus(OrderProcess.RETURN_TIMEOUT));
        }

        @Test
        void aParcelBackBeforeTheHandoverIsFulfilledRestockedAndRefunded() {
            OrderProcess process = decide(confirmed(), RETURNED);

            assertRefunded(process, OrderStatus.RETURNED_TO_ORIGIN, null, RefundReason.RETURNED_TO_ORIGIN,
                    new FulfillReservation(ORDER), new RestockReturn(ORDER));
        }

        @Test
        void aDeliveryCompletesTheOrder() {
            OrderProcess process = decide(shipped(), DELIVERED);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.DELIVERED, null, Step.DONE, null);
        }

        @Test
        void aReturnFailsTheDelivery() {
            OrderProcess process = decide(shipped(), RETURN_INITIATED);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.DELIVERY_FAILED, null, Step.AWAITING_RETURN,
                    NOW.plus(OrderProcess.RETURN_TIMEOUT));
        }

        @Test
        void aParcelBackBeforeItsReturnWasReportedIsRestockedAndRefunded() {
            OrderProcess process = decide(shipped(), RETURNED);

            assertRefunded(process, OrderStatus.RETURNED_TO_ORIGIN, null, RefundReason.RETURNED_TO_ORIGIN,
                    new RestockReturn(ORDER));
        }

        @Test
        void theParcelBackIsRestockedAndRefunded() {
            OrderProcess process = decide(shipped().then(RETURN_INITIATED), RETURNED);

            assertRefunded(process, OrderStatus.RETURNED_TO_ORIGIN, null, RefundReason.RETURNED_TO_ORIGIN,
                    new RestockReturn(ORDER));
        }
    }

    @Nested
    class CancellingPayment {

        private final Scenario cancelling = awaitingPayment().cancelled();
        private final Scenario awaitingOutcome = awaitingPayment().cancelled().then(CANCEL_REFUSED);

        @Test
        void aCancelledFailedOrExpiredPaymentReleasesEverything() {
            for (Scenario scenario : List.of(cancelling, awaitingOutcome)) {
                for (Object outcome : List.of(PAYMENT_CANCELLED, PAYMENT_FAILED, PAYMENT_EXPIRED)) {
                    assertCancelledWithRelease(decide(scenario, outcome), OrderReason.CUSTOMER);
                }
            }
        }

        @Test
        void aRefusalWaitsForTheAttemptsOutcomeUntilTheHoldExpires() {
            OrderProcess process = decide(cancelling, CANCEL_REFUSED);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.CANCELLING, null, Step.AWAITING_PAYMENT_OUTCOME, HOLD_EXPIRY);
        }

        @Test
        void aSuccessIsRefundedAndReleased() {
            for (Scenario scenario : List.of(cancelling, awaitingOutcome)) {
                OrderProcess process = decide(scenario, PAYMENT_SUCCEEDED);

                assertRefunded(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, RefundReason.ORDER_CANCELLED,
                        RELEASE_STOCK, RELEASE_COUPON);
                assertThat(process.state().paymentSucceeded()).isTrue();
            }
        }

        @Test
        void aPendingAttemptIsCheckedAgainInFiveMinutes() {
            OrderProcess process = decide(awaitingOutcome, PAYMENT_PENDING);

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.CANCELLING, null, Step.AWAITING_PAYMENT_OUTCOME,
                    NOW.plus(OrderProcess.RECHECK_INTERVAL));
        }

        @Test
        void supportsReasonIsTheOrdersReason() {
            Scenario bySupport = awaitingPayment().cancelledBy(
                    Cancellation.bySupport(CancelCode.SUSPECTED_FRAUD, null, T0));

            assertCancelledWithRelease(decide(bySupport, PAYMENT_CANCELLED), OrderReason.SUPPORT);
        }
    }

    @Nested
    class CancellingShipment {

        private final Scenario cancelling = confirmed().cancelled();

        @Test
        void aCancelledShipmentIsRefundedAndReleased() {
            OrderProcess process = decide(cancelling, SHIPMENT_CANCELLED);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.CUSTOMER, RefundReason.ORDER_CANCELLED,
                    RELEASE_STOCK, RELEASE_COUPON);
        }

        @Test
        void aRefusalMeansTheParcelWasHandedOver() {
            OrderProcess process = decide(cancelling, SHIPMENT_CANCEL_REFUSED);

            assertThat(process.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(process, OrderStatus.SHIPPED, null, Step.AWAITING_DELIVERY,
                    NOW.plus(OrderProcess.DELIVERY_TIMEOUT));
        }

        @Test
        void aShipmentEventFirstMeansTheCancellationLostTheRace() {
            OrderProcess handedOver = decide(cancelling, HANDED_OVER);
            assertThat(handedOver.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(handedOver, OrderStatus.SHIPPED, null, Step.AWAITING_DELIVERY,
                    NOW.plus(OrderProcess.DELIVERY_TIMEOUT));

            OrderProcess delivered = decide(cancelling, DELIVERED);
            assertThat(delivered.commands()).containsExactly(new FulfillReservation(ORDER));
            assertState(delivered, OrderStatus.DELIVERED, null, Step.DONE, null);
        }
    }

    @Nested
    class Refunding {

        @Test
        void theInitiatedRefundEndsTheProcess() {
            OrderProcess process = decide(committing().then(LOST),
                    new RefundInitiated(ORDER, REFUND, RefundReason.STOCK_LOST_AFTER_PAYMENT, TOTAL));

            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.CANCELLED, OrderReason.STOCK_LOST_AFTER_PAYMENT, Step.DONE, null);
            assertThat(process.state().refundStatus()).isEqualTo(RefundStatus.INITIATED);
        }

        @Test
        void anotherRefundsReplyIsIgnored() {
            assertIgnored(committing().then(LOST).reloaded(),
                    new RefundInitiated(ORDER, REFUND, RefundReason.LATE_SUCCESS, TOTAL));
        }
    }

    @Nested
    class Done {

        private final Scenario expired = awaitingPayment().then(PAYMENT_EXPIRED);

        @Test
        void aSuccessForAnOrderNeverPaidIsRefunded() {
            OrderProcess process = decide(expired, PAYMENT_SUCCEEDED);

            assertRefunded(process, OrderStatus.CANCELLED, OrderReason.PAYMENT_EXPIRED, RefundReason.LATE_SUCCESS);
            assertThat(process.state().paymentSucceeded()).isTrue();
        }

        @Test
        void aSuccessForAnOrderAlreadyPaidIsADuplicate() {
            assertIgnored(shipped().then(DELIVERED).reloaded(), PAYMENT_SUCCEEDED);
            assertIgnored(expired.then(PAYMENT_SUCCEEDED,
                    new RefundInitiated(ORDER, REFUND, RefundReason.LATE_SUCCESS, TOTAL)).reloaded(),
                    PAYMENT_SUCCEEDED);
        }

        @Test
        void aCouponReservedForARejectedOrCancelledOrderIsReleased() {
            OrderProcess rejected = decide(order().then(STOCK_RESERVED, COUPON_UNAVAILABLE), COUPON_RESERVED);
            assertThat(rejected.commands()).containsExactly(RELEASE_COUPON);
            assertState(rejected, OrderStatus.REJECTED, OrderReason.COUPON_UNAVAILABLE, Step.DONE, null);

            OrderProcess cancelled = decide(expired, COUPON_RESERVED);
            assertThat(cancelled.commands()).containsExactly(RELEASE_COUPON);
            assertState(cancelled, OrderStatus.CANCELLED, OrderReason.PAYMENT_EXPIRED, Step.DONE, null);
        }

        @Test
        void aCouponReservedForADeliveredOrderIsADuplicate() {
            assertIgnored(shipped().then(DELIVERED).reloaded(), COUPON_RESERVED);
        }
    }

    @Nested
    class Cancellations {

        @Test
        void aPlacedOrderIsCancellingAndItsStepGoesOn() {
            for (Scenario placed : List.of(order(), order().then(STOCK_RESERVED),
                    order().then(STOCK_RESERVED, COUPON_RESERVED))) {
                OrderProcess process = placed.reloaded();
                Step step = process.step();
                Instant deadline = process.state().deadlineAt();

                assertThat(process.requestCancellation(Cancellation.byCustomer(NOW), NOW))
                        .isEqualTo(CancelOutcome.REQUESTED);
                assertThat(process.commands()).isEmpty();
                assertState(process, OrderStatus.CANCELLING, null, step, deadline);
                assertThat(process.state().cancellation()).isEqualTo(Cancellation.byCustomer(NOW));
            }
        }

        @Test
        void aPaymentWaitingToBePaidIsCancelled() {
            OrderProcess process = awaitingPayment().reloaded();

            assertThat(process.requestCancellation(Cancellation.byCustomer(NOW), NOW))
                    .isEqualTo(CancelOutcome.REQUESTED);
            assertThat(process.commands()).containsExactly(new CancelPayment(ORDER));
            assertState(process, OrderStatus.CANCELLING, null, Step.CANCELLING_PAYMENT,
                    NOW.plus(OrderProcess.CANCEL_TIMEOUT));
            assertThat(process.state().checkoutUrl()).isNull();
        }

        @Test
        void aPaidOrderCommittingItsStockWaitsForTheCommit() {
            OrderProcess process = committing().reloaded();

            assertThat(process.requestCancellation(Cancellation.byCustomer(NOW), NOW))
                    .isEqualTo(CancelOutcome.REQUESTED);
            assertThat(process.commands()).isEmpty();
            assertState(process, OrderStatus.CANCELLING, null, Step.COMMITTING_STOCK,
                    T0.plus(OrderProcess.STEP_TIMEOUT));
        }

        @Test
        void aConfirmedOrdersShipmentIsCancelled() {
            OrderProcess process = confirmed().reloaded();

            assertThat(process.requestCancellation(Cancellation.byCustomer(NOW), NOW))
                    .isEqualTo(CancelOutcome.REQUESTED);
            assertThat(process.commands()).containsExactly(new CancelShipment(ORDER));
            assertState(process, OrderStatus.CANCELLING, null, Step.CANCELLING_SHIPMENT,
                    NOW.plus(OrderProcess.CANCEL_TIMEOUT));
        }

        @Test
        void aCancellingOrCancelledOrderStaysAsItIs() {
            for (Scenario scenario : List.of(awaitingPayment().cancelled(), awaitingPayment().then(PAYMENT_FAILED))) {
                OrderProcess process = scenario.reloaded();
                ProcessState before = process.state();

                assertThat(process.requestCancellation(Cancellation.bySupport(CancelCode.OTHER, "dup", NOW), NOW))
                        .isEqualTo(CancelOutcome.ALREADY_CANCELLING);
                assertThat(process.changed()).isFalse();
                assertThat(process.state()).isEqualTo(before);
            }
        }

        @Test
        void shippedDeliveredReturnedAndRejectedOrdersCannotBeCancelled() {
            for (Scenario scenario : List.of(shipped(), shipped().then(DELIVERED), shipped().then(RETURN_INITIATED),
                    shipped().then(RETURNED), order().then(STOCK_SHORT))) {
                OrderProcess process = scenario.reloaded();
                ProcessState before = process.state();

                assertThat(process.requestCancellation(Cancellation.byCustomer(NOW), NOW))
                        .isEqualTo(CancelOutcome.REFUSED);
                assertThat(process.changed()).isFalse();
                assertThat(process.state()).isEqualTo(before);
            }
        }
    }

    @Nested
    class Deadlines {

        @Test
        void aDeadlineNotYetPassedChangesNothing() {
            OrderProcess process = order().reloaded();

            process.onDeadline(T0.plus(OrderProcess.STEP_TIMEOUT).minusMillis(1));

            assertThat(process.changed()).isFalse();
            assertThat(process.commands()).isEmpty();
        }

        @Test
        void aStepWaitingForAReplySendsItsCommandAgainAndAlertsFromTheThirdAttempt() {
            OrderProcess process = order().reloaded();
            Instant due = T0.plus(OrderProcess.STEP_TIMEOUT);

            process.onDeadline(due);
            assertThat(process.commands()).containsExactly(new ReserveStock(ORDER, LINES, HOLD));
            assertThat(process.state().attempts()).isEqualTo(2);
            assertThat(process.state().deadlineAt()).isEqualTo(due.plus(OrderProcess.STEP_TIMEOUT));
            assertThat(process.overdue()).isFalse();

            OrderProcess again = new OrderProcess(facts(COUPON), process.state(), SETTINGS);
            again.onDeadline(due.plus(OrderProcess.STEP_TIMEOUT));
            assertThat(again.state().attempts()).isEqualTo(3);
            assertThat(again.overdue()).isTrue();
        }

        @Test
        void eachWaitingStepSendsItsOwnCommandAgain() {
            Map<Step, Object> expected = Map.of(
                    Step.RESERVING_COUPON, new ReserveCoupon(ORDER, COUPON, CUSTOMER),
                    Step.CREATING_PAYMENT, CREATE_PAYMENT,
                    Step.COMMITTING_STOCK, new CommitReservation(ORDER),
                    Step.CANCELLING_PAYMENT, new CancelPayment(ORDER),
                    Step.CANCELLING_SHIPMENT, new CancelShipment(ORDER),
                    Step.REFUNDING, new RefundPayment(ORDER, TOTAL, RefundReason.STOCK_LOST_AFTER_PAYMENT),
                    Step.AWAITING_PAYMENT, new CheckPayment(ORDER),
                    Step.AWAITING_PAYMENT_OUTCOME, new CheckPayment(ORDER));
            expected.forEach((step, command) -> {
                OrderProcess process = inStep(step);
                Instant due = process.state().deadlineAt();

                process.onDeadline(due);

                assertThat(process.commands()).as(step.name()).containsExactly(command);
                assertThat(process.step()).isEqualTo(step);
                Duration wait = switch (step) {
                    case AWAITING_PAYMENT, AWAITING_PAYMENT_OUTCOME -> OrderProcess.RECHECK_INTERVAL;
                    case CANCELLING_PAYMENT, CANCELLING_SHIPMENT, REFUNDING -> OrderProcess.CANCEL_TIMEOUT;
                    default -> OrderProcess.STEP_TIMEOUT;
                };
                assertThat(process.state().deadlineAt()).as(step.name()).isEqualTo(due.plus(wait));
            });
        }

        @Test
        void aPaymentIsCheckedAtTheHoldsExpiryAndAlertsFromTheThirdCheck() {
            OrderProcess process = awaitingPayment().reloaded();
            assertThat(process.state().deadlineAt()).isEqualTo(HOLD_EXPIRY);

            process.onDeadline(HOLD_EXPIRY);
            assertThat(process.state().attempts()).isEqualTo(1);
            assertThat(process.overdue()).isFalse();
            for (int attempt = 2; attempt <= 3; attempt++) {
                process = new OrderProcess(facts(COUPON), process.state(), SETTINGS);
                process.onDeadline(process.state().deadlineAt());
            }
            assertThat(process.state().attempts()).isEqualTo(3);
            assertThat(process.overdue()).isTrue();
        }

        @Test
        void stepsWaitingForTheParcelAlertAndWaitAgain() {
            Map<Step, Duration> waits = Map.of(Step.AWAITING_HANDOVER, OrderProcess.HANDOVER_TIMEOUT,
                    Step.AWAITING_DELIVERY, OrderProcess.DELIVERY_TIMEOUT,
                    Step.AWAITING_RETURN, OrderProcess.RETURN_TIMEOUT);
            waits.forEach((step, wait) -> {
                OrderProcess process = inStep(step);
                Instant due = process.state().deadlineAt();

                process.onDeadline(due);

                assertThat(process.commands()).as(step.name()).isEmpty();
                assertThat(process.overdue()).as(step.name()).isTrue();
                assertThat(process.state().attempts()).isEqualTo(1);
                assertThat(process.state().deadlineAt()).isEqualTo(due.plus(wait));
            });
        }

        @Test
        void aFinishedProcessHasNoDeadline() {
            OrderProcess process = inStep(Step.DONE);

            process.onDeadline(NOW.plus(Duration.ofDays(365)));

            assertThat(process.changed()).isFalse();
        }
    }

    /** What each step does with a message; any other message changes nothing (LLD §6.5). */
    private static final Map<Step, Set<Class<?>>> EXPECTED = Map.ofEntries(
            Map.entry(Step.RESERVING_STOCK, Set.of(StockReserved.class, StockReservationFailed.class)),
            Map.entry(Step.RESERVING_COUPON, Set.of(CouponReserved.class, CouponUnavailable.class)),
            Map.entry(Step.CREATING_PAYMENT, Set.of(PaymentCreated.class, PaymentCreationFailed.class,
                    PaymentSucceeded.class, PaymentFailed.class, PaymentExpired.class, PaymentCancelled.class)),
            Map.entry(Step.AWAITING_PAYMENT, Set.of(PaymentSucceeded.class, PaymentFailed.class,
                    PaymentExpired.class, PaymentCancelled.class, PaymentPending.class)),
            Map.entry(Step.COMMITTING_STOCK, Set.of(ReservationCommitted.class, ReservationLost.class)),
            Map.entry(Step.AWAITING_HANDOVER, Set.of(ShipmentHandedOver.class, ShipmentDelivered.class,
                    ShipmentReturnInitiated.class, ShipmentReturnedToOrigin.class)),
            Map.entry(Step.AWAITING_DELIVERY, Set.of(ShipmentDelivered.class, ShipmentReturnInitiated.class,
                    ShipmentReturnedToOrigin.class)),
            Map.entry(Step.AWAITING_RETURN, Set.of(ShipmentReturnedToOrigin.class)),
            Map.entry(Step.CANCELLING_PAYMENT, Set.of(PaymentCancelled.class, PaymentFailed.class,
                    PaymentExpired.class, PaymentSucceeded.class, PaymentCancelRefused.class)),
            Map.entry(Step.AWAITING_PAYMENT_OUTCOME, Set.of(PaymentCancelled.class, PaymentFailed.class,
                    PaymentExpired.class, PaymentSucceeded.class, PaymentPending.class)),
            Map.entry(Step.CANCELLING_SHIPMENT, Set.of(ShipmentCancelled.class, ShipmentCancelRefused.class,
                    ShipmentHandedOver.class, ShipmentDelivered.class, ShipmentReturnInitiated.class,
                    ShipmentReturnedToOrigin.class)),
            Map.entry(Step.REFUNDING, Set.of(RefundInitiated.class)),
            Map.entry(Step.DONE, Set.of()));

    /** Every reply and event the process consumes. */
    private static List<Object> allMessages() {
        return List.of(STOCK_RESERVED, STOCK_SHORT, COMMITTED, LOST, COUPON_RESERVED, COUPON_UNAVAILABLE,
                PAYMENT_CREATED, PAYMENT_CREATION_FAILED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_EXPIRED,
                PAYMENT_CANCELLED, CANCEL_REFUSED, PAYMENT_PENDING,
                new RefundInitiated(ORDER, REFUND, RefundReason.STOCK_LOST_AFTER_PAYMENT, TOTAL),
                SHIPMENT_CANCELLED, SHIPMENT_CANCEL_REFUSED, HANDED_OVER, DELIVERED, RETURN_INITIATED, RETURNED);
    }

    static Stream<Arguments> unexpectedMessages() {
        List<Arguments> cells = new ArrayList<>();
        for (Step step : Step.values()) {
            for (Object message : allMessages()) {
                if (!EXPECTED.get(step).contains(message.getClass())) {
                    cells.add(Arguments.of(step, message.getClass().getSimpleName(), message));
                }
            }
        }
        return cells.stream();
    }

    static Stream<Arguments> expectedMessages() {
        List<Arguments> cells = new ArrayList<>();
        for (Step step : Step.values()) {
            for (Object message : allMessages()) {
                if (EXPECTED.get(step).contains(message.getClass())) {
                    cells.add(Arguments.of(step, message.getClass().getSimpleName(), message));
                }
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} ignores {1}")
    @MethodSource("unexpectedMessages")
    void aMessageTheStepDoesNotExpectChangesNothing(Step step, String name, Object message) {
        assertIgnored(inStep(step), message);
    }

    @ParameterizedTest(name = "{0} acts on {1}")
    @MethodSource("expectedMessages")
    void aMessageTheStepExpectsChangesIt(Step step, String name, Object message) {
        OrderProcess process = inStep(step);

        process.decide(message, NOW);

        assertThat(process.changed()).isTrue();
    }

    @Test
    void theRepresentativeProcessesAreInTheirSteps() {
        for (Step step : Step.values()) {
            assertThat(inStep(step).step()).isEqualTo(step);
        }
    }

    @Nested
    class StatusTable {

        private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
                OrderStatus.PLACED, EnumSet.of(OrderStatus.AWAITING_PAYMENT, OrderStatus.REJECTED,
                        OrderStatus.CANCELLING),
                OrderStatus.AWAITING_PAYMENT, EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED,
                        OrderStatus.CANCELLING),
                OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.CANCELLING, OrderStatus.SHIPPED),
                OrderStatus.CANCELLING, EnumSet.of(OrderStatus.CANCELLED, OrderStatus.SHIPPED),
                OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED),
                OrderStatus.DELIVERY_FAILED, EnumSet.of(OrderStatus.RETURNED_TO_ORIGIN));

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        void onlyTheLifecyclesChangesAreAllowed(OrderStatus from) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to)
                        .isEqualTo(ALLOWED.getOrDefault(from, Set.of()).contains(to));
            }
        }

        @Test
        void aChangeOutsideTheTableIsABug() {
            OrderProcess delivered = inStep(Step.DONE);
            OrderProcess rejected = order().then(STOCK_SHORT).reloaded();

            assertThatThrownBy(() -> new OrderProcess(facts(COUPON), withStep(delivered.state(),
                    Step.AWAITING_DELIVERY), SETTINGS).decide(RETURN_INITIATED, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot move from DELIVERED to DELIVERY_FAILED");
            assertThatThrownBy(() -> new OrderProcess(facts(COUPON), withStep(rejected.state(),
                    Step.CREATING_PAYMENT), SETTINGS).decide(PAYMENT_CREATED, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot move from REJECTED to AWAITING_PAYMENT");
        }

        /** A state no decision produces: the status of one step with another step. */
        private static ProcessState withStep(ProcessState state, Step step) {
            return new ProcessState(state.status(), state.reason(), state.shortSku(), state.paymentId(),
                    state.checkoutUrl(), state.refundAmountPaise(), state.refundStatus(), state.refundReason(), step,
                    state.cancellation(), state.paymentSucceeded(), state.holdExpiresAt(), NOW, 1, state.version());
        }
    }

    private static OrderFacts facts(UUID coupon) {
        return new OrderFacts(ORDER, CUSTOMER, coupon, TOTAL, LINES, ADDRESS);
    }

    private static OrderProcess decide(Scenario scenario, Object message) {
        OrderProcess process = scenario.reloaded();
        process.decide(message, NOW);
        return process;
    }

    private static void assertIgnored(OrderProcess process, Object message) {
        ProcessState before = process.state();

        process.decide(message, NOW);

        assertThat(process.changed()).as("changed").isFalse();
        assertThat(process.commands()).isEmpty();
        assertThat(process.state()).isEqualTo(before);
    }

    private static void assertState(OrderProcess process, OrderStatus status, OrderReason reason, Step step,
            Instant deadline) {
        ProcessState state = process.state();
        assertThat(state.status()).as("status").isEqualTo(status);
        assertThat(state.reason()).as("reason").isEqualTo(reason);
        assertThat(state.step()).as("step").isEqualTo(step);
        assertThat(state.deadlineAt()).as("deadline").isEqualTo(deadline);
    }

    private static void assertCancelledWithRelease(OrderProcess process, OrderReason reason) {
        assertThat(process.commands()).containsExactly(RELEASE_STOCK, RELEASE_COUPON);
        assertState(process, OrderStatus.CANCELLED, reason, Step.DONE, null);
        assertThat(process.state().refundStatus()).as("refund").isNull();
    }

    /** The final status with a refund of the grand total requested, after {@code before}. */
    private static void assertRefunded(OrderProcess process, OrderStatus status, OrderReason reason,
            RefundReason why, Object... before) {
        List<Object> commands = new ArrayList<>(List.of(before));
        commands.add(new RefundPayment(ORDER, TOTAL, why));
        assertThat(process.commands()).containsExactlyElementsOf(commands);
        assertState(process, status, reason, Step.REFUNDING, NOW.plus(OrderProcess.CANCEL_TIMEOUT));
        assertThat(process.state().refundAmountPaise()).isEqualTo(TOTAL);
        assertThat(process.state().refundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(process.state().refundReason()).isEqualTo(why);
    }

    /** A process driven through messages at T0, as placement and the participants would. */
    private static final class Scenario {

        private final OrderFacts facts;
        private OrderProcess process;

        Scenario(UUID coupon) {
            facts = facts(coupon);
            process = OrderProcess.start(facts, SETTINGS, T0);
        }

        private Scenario(OrderFacts facts, OrderProcess process) {
            this.facts = facts;
            this.process = process;
        }

        Scenario then(Object... messages) {
            OrderProcess next = reloaded();
            for (Object message : messages) {
                next.decide(message, T0);
                assertThat(next.changed()).as("setting up with " + message).isTrue();
                next = new OrderProcess(facts, next.state(), SETTINGS);
            }
            return new Scenario(facts, next);
        }

        Scenario cancelled() {
            return cancelledBy(Cancellation.byCustomer(T0));
        }

        Scenario cancelledBy(Cancellation cancellation) {
            OrderProcess next = reloaded();
            assertThat(next.requestCancellation(cancellation, T0)).isEqualTo(CancelOutcome.REQUESTED);
            return new Scenario(facts, next);
        }

        /** The process as the repository would load it: same state, no pending outputs. */
        OrderProcess reloaded() {
            return new OrderProcess(facts, process.state(), SETTINGS);
        }
    }
}
