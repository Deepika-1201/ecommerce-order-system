package com.ecommerce.fulfillment;

import com.ecommerce.platform.MessageType;
import java.util.UUID;

/**
 * The order saga's commands to Fulfillment, Fulfillment's replies, and the shipment events the saga consumes
 * (LLD §6.2). Replies and events come from aggregate {@code shipment}, keyed by the order id, with the shipment's
 * version.
 */
public final class ShipmentMessages {

    private ShipmentMessages() {
    }

    /** Ship the order to its delivery address snapshot (ADR-023). No reply: the shipment's events follow. */
    @MessageType(name = "fulfillment.create-shipment")
    public record CreateShipment(UUID orderId, UUID deliveryAddressId) {
    }

    /** Cancel before the handover: {@link ShipmentCancelled} or {@link ShipmentCancelRefused}. */
    @MessageType(name = "fulfillment.cancel-shipment")
    public record CancelShipment(UUID orderId) {
    }

    @MessageType(name = "fulfillment.shipment-cancelled")
    public record ShipmentCancelled(UUID orderId) {
    }

    /** The parcel was already handed over to the carrier. */
    @MessageType(name = "fulfillment.shipment-cancel-refused")
    public record ShipmentCancelRefused(UUID orderId) {
    }

    @MessageType(name = "fulfillment.shipment-handed-over")
    public record ShipmentHandedOver(UUID orderId) {
    }

    @MessageType(name = "fulfillment.shipment-delivered")
    public record ShipmentDelivered(UUID orderId) {
    }

    /** Delivery failed and the parcel is on its way back. */
    @MessageType(name = "fulfillment.shipment-return-initiated")
    public record ShipmentReturnInitiated(UUID orderId) {
    }

    @MessageType(name = "fulfillment.shipment-returned-to-origin")
    public record ShipmentReturnedToOrigin(UUID orderId) {
    }
}
