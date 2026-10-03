-- The payment simulator, until phase 7 replaces it with the gateway adapter (ADR-022, LLD §6.8).

CREATE TABLE simulated_payments (
    order_id     uuid        PRIMARY KEY,
    payment_id   uuid        UNIQUE,
    amount_paise bigint      CHECK (amount_paise >= 0),
    status       text        NOT NULL CHECK (status IN ('REQUIRES_PAYMENT', 'PROCESSING', 'SUCCEEDED', 'FAILED',
                                                        'EXPIRED', 'CANCELLED', 'CREATION_REFUSED')),
    expires_at   timestamptz,
    version      bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    -- A refused payment was never created, so it has none of a payment's fields.
    CONSTRAINT simulated_payments_created CHECK (
        (status = 'CREATION_REFUSED') = (payment_id IS NULL)
        AND (payment_id IS NULL) = (amount_paise IS NULL)
        AND (payment_id IS NULL) = (expires_at IS NULL))
);

CREATE TABLE simulated_refunds (
    order_id     uuid        NOT NULL REFERENCES simulated_payments (order_id),
    reason       text        NOT NULL CHECK (reason IN ('ORDER_CANCELLED', 'STOCK_LOST_AFTER_PAYMENT',
                                                        'RETURNED_TO_ORIGIN', 'LATE_SUCCESS')),
    refund_id    uuid        NOT NULL UNIQUE,
    amount_paise bigint      NOT NULL CHECK (amount_paise >= 0),
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (order_id, reason)
);
