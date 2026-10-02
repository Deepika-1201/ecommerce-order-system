package com.ecommerce.customer.domain;

import com.ecommerce.shared.IndianState;
import java.util.UUID;

/** A delivery address. {@code phone} is normalized to {@code +91} and 10 digits. */
public record Address(
        UUID id,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String landmark,
        String city,
        IndianState state,
        String pinCode,
        boolean isDefault) {
}
