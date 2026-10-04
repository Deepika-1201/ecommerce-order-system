package com.ecommerce.fulfillment.web;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.fulfillment.ShipmentMessages.Line;
import com.ecommerce.fulfillment.ShipmentStatus;
import com.ecommerce.fulfillment.domain.ShipmentOperations.WarehouseShipment;
import java.util.List;
import java.util.UUID;

/** Response bodies of the warehouse's shipment API (LLD §8.11). */
final class FulfillmentApi {

    private FulfillmentApi() {
    }

    record ShipmentLineResponse(String sku, int quantity) {

        static ShipmentLineResponse of(Line line) {
            return new ShipmentLineResponse(line.sku(), line.quantity());
        }
    }

    /** Where the parcel goes, for its label. */
    record ShipmentAddressResponse(String recipientName, String phone, String line1, String line2, String landmark,
                                   String city, String stateCode, String pinCode) {

        static ShipmentAddressResponse of(AddressSnapshot address) {
            return address == null ? null
                    : new ShipmentAddressResponse(address.recipientName(), address.phone(), address.line1(),
                            address.line2(), address.landmark(), address.city(), address.state().code(),
                            address.pinCode());
        }
    }

    record WarehouseShipmentResponse(UUID id, UUID orderId, ShipmentStatus status, String carrier, String awb,
                                     List<ShipmentLineResponse> lines, ShipmentAddressResponse deliveryAddress) {

        static WarehouseShipmentResponse of(WarehouseShipment shipment) {
            return new WarehouseShipmentResponse(shipment.id(), shipment.orderId(), shipment.status(),
                    shipment.carrier(), shipment.awb(), shipment.lines().stream().map(ShipmentLineResponse::of).toList(),
                    ShipmentAddressResponse.of(shipment.deliveryAddress()));
        }
    }

    record WarehouseShipmentList(List<WarehouseShipmentResponse> items, String nextCursor) {
    }
}
