package com.ecommerce.customer;

import com.ecommerce.shared.IndianState;
import java.util.UUID;

/** A copy of an address as an order was placed with it; it never changes (ADR-023). */
public record AddressSnapshot(
        UUID id,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String landmark,
        String city,
        IndianState state,
        String pinCode) {
}
