package com.ecommerce.ordering.domain;

import com.ecommerce.customer.AddressSnapshot;

/** An order with its address snapshots, composed at read time (ADR-023). */
public record OrderView(Order order, AddressSnapshot deliveryAddress, AddressSnapshot billingAddress) {
}
