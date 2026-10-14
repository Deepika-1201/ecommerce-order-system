package com.ecommerce.ordering.domain;

import com.ecommerce.fulfillment.Shipments;
import com.ecommerce.platform.Correlation;
import com.ecommerce.platform.MessageType;
import com.ecommerce.platform.Messages;
import com.ecommerce.platform.Origin;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class OrderEvents {

    static final String TOPIC = "ecom.ordering.order-events";

    private final OrderRepository orders;
    private final Messages messages;
    private final Shipments shipments;

    OrderEvents(OrderRepository orders, Messages messages, Shipments shipments) {
        this.orders = orders;
        this.messages = messages;
        this.shipments = shipments;
    }

    void publish(OrderProcess process, Origin origin, Correlation correlation) {
        boolean placed = process.sequence() == 1 && process.status() == OrderStatus.PLACED;
        if (!placed && process.initialStatus() == process.status()) {
            return;
        }
        if (process.status() == OrderStatus.AWAITING_PAYMENT || process.status() == OrderStatus.CANCELLING) {
            return;
        }
        Order order = orders.find(process.orderId()).orElseThrow();
        Object event = switch (order.status()) {
            case PLACED -> new Placed(order.id(), order.number(), order.customerId(),
                    order.totals().grandTotalPaise(), order.lines().size());
            case CONFIRMED -> new Confirmed(order.id(), order.customerId(), order.paymentId(),
                    order.totals().grandTotalPaise());
            case REJECTED -> new Rejected(order.id(), order.customerId(), order.reason(), order.shortSku());
            case CANCELLED -> new Cancelled(order.id(), order.customerId(), order.reason(),
                    order.refundAmountPaise() == null ? 0 : order.refundAmountPaise(), order.refundStatus());
            case SHIPPED -> {
                Shipments.ShipmentView shipment = shipments.ofOrder(order.id()).orElseThrow();
                yield new Shipped(order.id(), order.customerId(), shipment.carrier(), shipment.awb());
            }
            case DELIVERED -> new Delivered(order.id(), order.customerId(), order.updatedAt());
            case DELIVERY_FAILED -> new DeliveryFailed(order.id(), order.customerId());
            case RETURNED_TO_ORIGIN -> new ReturnedToOrigin(order.id(), order.customerId(),
                    order.refundAmountPaise(), order.refundStatus());
            case AWAITING_PAYMENT, CANCELLING -> throw new IllegalStateException("Not an integration milestone");
        };
        messages.publish(event, origin, correlation);
    }

    @MessageType(name = "order.placed", topic = TOPIC)
    record Placed(UUID orderId, String orderNumber, UUID customerId, long amountPaise, int lineCount) {
    }

    @MessageType(name = "order.confirmed", topic = TOPIC)
    record Confirmed(UUID orderId, UUID customerId, UUID paymentId, long amountPaise) {
    }

    @MessageType(name = "order.rejected", topic = TOPIC)
    record Rejected(UUID orderId, UUID customerId, OrderReason reason, String sku) {
    }

    @MessageType(name = "order.cancelled", topic = TOPIC)
    record Cancelled(UUID orderId, UUID customerId, OrderReason reason, long refundAmountPaise,
            RefundStatus refundStatus) {
    }

    @MessageType(name = "order.shipped", topic = TOPIC)
    record Shipped(UUID orderId, UUID customerId, String carrier, String awb) {
    }

    @MessageType(name = "order.delivered", topic = TOPIC)
    record Delivered(UUID orderId, UUID customerId, Instant deliveredAt) {
    }

    @MessageType(name = "order.delivery_failed", topic = TOPIC)
    record DeliveryFailed(UUID orderId, UUID customerId) {
    }

    @MessageType(name = "order.returned_to_origin", topic = TOPIC)
    record ReturnedToOrigin(UUID orderId, UUID customerId, long refundAmountPaise, RefundStatus refundStatus) {
    }
}