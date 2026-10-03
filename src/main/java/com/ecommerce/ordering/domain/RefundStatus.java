package com.ecommerce.ordering.domain;

/**
 * A refund's progress as the order shows it (LLD §6.4, §7.9): asked of Payments, confirmed by it, then ended. The saga
 * copies these milestones from Payments' events.
 */
public enum RefundStatus {
    REQUESTED,
    INITIATED,
    SUCCEEDED,
    FAILED
}
