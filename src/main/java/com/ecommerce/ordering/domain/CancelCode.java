package com.ecommerce.ordering.domain;

/** Support's reason for cancelling an order (LLD §6.6); {@code OTHER} needs a note. */
public enum CancelCode {
    CUSTOMER_REQUEST,
    SUSPECTED_FRAUD,
    ITEM_UNAVAILABLE,
    PRICING_ERROR,
    OTHER
}
