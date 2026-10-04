package com.ecommerce.ordering.domain;

import com.ecommerce.customer.AddressSnapshot;
import com.ecommerce.fulfillment.Shipments.ShipmentView;

/**
 * An order with its address snapshots (ADR-023) and its shipment, if it has one (LLD §8.11), composed at read time.
 */
public record OrderView(Order order, AddressSnapshot deliveryAddress, AddressSnapshot billingAddress,
                        ShipmentView shipment) {
}
