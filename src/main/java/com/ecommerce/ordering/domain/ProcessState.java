package com.ecommerce.ordering.domain;

import com.ecommerce.payments.PaymentMessages.RefundReason;
import java.time.Instant;
import java.util.UUID;

/**
 * What an order's process stores (LLD §6.4, §6.5): the order's fields that change, and the process's own. The
 * deadline is {@code null} only when the step is {@code DONE}; {@code version} counts the saved changes.
 */
record ProcessState(
        OrderStatus status,
        OrderReason reason,
        String shortSku,
        UUID paymentId,
        String checkoutUrl,
        Long refundAmountPaise,
        RefundStatus refundStatus,
        RefundReason refundReason,
        Step step,
        Cancellation cancellation,
        boolean paymentSucceeded,
        Instant holdExpiresAt,
        Instant deadlineAt,
        int attempts,
        long version) {

    /** A new order's state, before its process starts. */
    static ProcessState placed() {
        return new ProcessState(OrderStatus.PLACED, null, null, null, null, null, null, null, Step.RESERVING_STOCK,
                null, false, null, null, 0, 0);
    }
}
