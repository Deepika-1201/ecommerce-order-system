package com.ecommerce.ordering.domain;

/** Where an order's process is: what it waits for (LLD §6.5). */
enum Step {
    RESERVING_STOCK,
    RESERVING_COUPON,
    CREATING_PAYMENT,
    AWAITING_PAYMENT,
    COMMITTING_STOCK,
    AWAITING_HANDOVER,
    AWAITING_DELIVERY,
    AWAITING_RETURN,
    CANCELLING_PAYMENT,
    /** The cancellation was refused while an attempt to pay was in flight: wait for its outcome. */
    AWAITING_PAYMENT_OUTCOME,
    CANCELLING_SHIPMENT,
    REFUNDING,
    DONE
}
