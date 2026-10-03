package com.ecommerce.ordering.domain;

/** A refund's progress as the order shows it: asked of Payments, then confirmed by it (LLD §6.4). */
public enum RefundStatus {
    REQUESTED,
    INITIATED
}
