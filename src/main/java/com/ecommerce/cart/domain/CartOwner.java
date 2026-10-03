package com.ecommerce.cart.domain;

import java.util.UUID;

/** Who a cart belongs to: a customer, or whoever holds a guest cart's token (ADR-020). */
public sealed interface CartOwner {

    record Customer(UUID customerId) implements CartOwner {
    }

    /** Identified only by the SHA-256 hash of the cart token. */
    record Guest(byte[] tokenHash) implements CartOwner {
    }
}
