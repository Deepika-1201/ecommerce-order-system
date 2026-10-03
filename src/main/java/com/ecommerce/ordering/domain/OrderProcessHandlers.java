package com.ecommerce.ordering.domain;

import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelRefused;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentCancelled;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentDelivered;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentHandedOver;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnInitiated;
import com.ecommerce.fulfillment.ShipmentMessages.ShipmentReturnedToOrigin;
import com.ecommerce.inventory.StockMessages.ReservationCommitted;
import com.ecommerce.inventory.StockMessages.ReservationLost;
import com.ecommerce.inventory.StockMessages.StockReservationFailed;
import com.ecommerce.inventory.StockMessages.StockReserved;
import com.ecommerce.payments.PaymentMessages.PaymentCancelRefused;
import com.ecommerce.payments.PaymentMessages.PaymentCancelled;
import com.ecommerce.payments.PaymentMessages.PaymentCreated;
import com.ecommerce.payments.PaymentMessages.PaymentCreationFailed;
import com.ecommerce.payments.PaymentMessages.PaymentExpired;
import com.ecommerce.payments.PaymentMessages.PaymentFailed;
import com.ecommerce.payments.PaymentMessages.PaymentPending;
import com.ecommerce.payments.PaymentMessages.PaymentSucceeded;
import com.ecommerce.payments.PaymentMessages.RefundInitiated;
import com.ecommerce.platform.HandlesMessage;
import com.ecommerce.platform.IncomingMessage;
import com.ecommerce.pricing.CouponMessages.CouponReserved;
import com.ecommerce.pricing.CouponMessages.CouponUnavailable;
import org.springframework.stereotype.Component;

/**
 * Every reply and event the order process consumes (LLD §6.2), one consumer per type. The outbox orders messages per
 * consumer, never across types, so the process decides what each means in every step.
 */
@Component
class OrderProcessHandlers {

    private final OrderProcesses processes;

    OrderProcessHandlers(OrderProcesses processes) {
        this.processes = processes;
    }

    @HandlesMessage(consumer = "ordering.stock-reserved")
    void stockReserved(IncomingMessage<StockReserved> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.stock-reservation-failed")
    void stockReservationFailed(IncomingMessage<StockReservationFailed> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.reservation-committed")
    void reservationCommitted(IncomingMessage<ReservationCommitted> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.reservation-lost")
    void reservationLost(IncomingMessage<ReservationLost> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.coupon-reserved")
    void couponReserved(IncomingMessage<CouponReserved> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.coupon-unavailable")
    void couponUnavailable(IncomingMessage<CouponUnavailable> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-created")
    void paymentCreated(IncomingMessage<PaymentCreated> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-creation-failed")
    void paymentCreationFailed(IncomingMessage<PaymentCreationFailed> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-succeeded")
    void paymentSucceeded(IncomingMessage<PaymentSucceeded> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-failed")
    void paymentFailed(IncomingMessage<PaymentFailed> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-expired")
    void paymentExpired(IncomingMessage<PaymentExpired> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-cancelled")
    void paymentCancelled(IncomingMessage<PaymentCancelled> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-cancel-refused")
    void paymentCancelRefused(IncomingMessage<PaymentCancelRefused> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.payment-pending")
    void paymentPending(IncomingMessage<PaymentPending> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.refund-initiated")
    void refundInitiated(IncomingMessage<RefundInitiated> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-cancelled")
    void shipmentCancelled(IncomingMessage<ShipmentCancelled> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-cancel-refused")
    void shipmentCancelRefused(IncomingMessage<ShipmentCancelRefused> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-handed-over")
    void shipmentHandedOver(IncomingMessage<ShipmentHandedOver> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-delivered")
    void shipmentDelivered(IncomingMessage<ShipmentDelivered> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-return-initiated")
    void shipmentReturnInitiated(IncomingMessage<ShipmentReturnInitiated> message) {
        processes.handle(message.payload().orderId(), message);
    }

    @HandlesMessage(consumer = "ordering.shipment-returned-to-origin")
    void shipmentReturnedToOrigin(IncomingMessage<ShipmentReturnedToOrigin> message) {
        processes.handle(message.payload().orderId(), message);
    }
}
