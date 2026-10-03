package com.ecommerce.ordering.domain;

import java.time.Instant;
import org.springframework.util.Assert;

/**
 * A request to cancel an order (LLD §6.6): the customer's, or support's with a reason code and a note, which
 * {@code OTHER} needs. {@code reason} is the order's reason once it is cancelled.
 */
record Cancellation(OrderReason reason, CancelCode code, String note, Instant requestedAt) {

    Cancellation {
        Assert.notNull(requestedAt, "requestedAt must not be null");
        if (reason == OrderReason.CUSTOMER) {
            Assert.isTrue(code == null && note == null, "A customer's cancellation has no code or note");
        } else {
            Assert.isTrue(reason == OrderReason.SUPPORT, "Only customers and support cancel orders");
            Assert.notNull(code, "Support's cancellation needs a reason code");
            Assert.isTrue(code != CancelCode.OTHER || (note != null && !note.isBlank()),
                    "A cancellation for another reason needs a note");
        }
    }

    static Cancellation byCustomer(Instant now) {
        return new Cancellation(OrderReason.CUSTOMER, null, null, now);
    }

    static Cancellation bySupport(CancelCode code, String note, Instant now) {
        return new Cancellation(OrderReason.SUPPORT, code, note, now);
    }
}
