package com.ecommerce.customer.domain;

import com.ecommerce.shared.IndianState;

/** The fields a customer provides for an address, already validated and normalized. */
public record AddressDetails(
        String recipientName,
        String phone,
        String line1,
        String line2,
        String landmark,
        String city,
        IndianState state,
        String pinCode) {
}
