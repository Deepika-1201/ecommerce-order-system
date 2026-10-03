-- Payment records and refunds (LLD §7.4), which replace the phase 6 simulator (ADR-022).

DROP TABLE simulated_refunds;
DROP TABLE simulated_payments;

CREATE TABLE payment_records (
    id                  uuid        PRIMARY KEY,
    order_id            uuid        NOT NULL UNIQUE,
    customer_id         uuid        NOT NULL,
    amount_paise        bigint      NOT NULL CHECK (amount_paise > 0),
    expires_at          timestamptz NOT NULL,
    creation            text        NOT NULL CHECK (creation IN ('CREATING', 'CREATED', 'FAILED')),
    gateway_payment_id  text        UNIQUE,
    status              text        CHECK (status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING',
                                                      'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    gateway_version     bigint      NOT NULL DEFAULT 0 CHECK (gateway_version >= 0),
    checkout_url        text,
    cancel_requested    boolean     NOT NULL DEFAULT false,
    version             bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    -- Once the gateway has the payment, its status is known.
    CONSTRAINT payment_records_status CHECK ((status IS NULL) = (gateway_payment_id IS NULL)),
    -- A created payment has its checkout session; a failed creation may have left a payment without one.
    CONSTRAINT payment_records_creation CHECK (
        CASE creation
            WHEN 'CREATED' THEN gateway_payment_id IS NOT NULL AND checkout_url IS NOT NULL
            ELSE checkout_url IS NULL
        END)
);

CREATE TABLE refunds (
    id                 uuid        PRIMARY KEY,
    order_id           uuid        NOT NULL REFERENCES payment_records (order_id),
    reason             text        CHECK (reason IN ('ORDER_CANCELLED', 'STOCK_LOST_AFTER_PAYMENT', 'RETURNED_TO_ORIGIN',
                                                     'LATE_SUCCESS')),
    amount_paise       bigint      NOT NULL CHECK (amount_paise > 0),
    initiated_by       text        NOT NULL CHECK (initiated_by IN ('MERCHANT', 'SYSTEM_LATE_SUCCESS',
                                                                    'SYSTEM_DUPLICATE_SUCCESS')),
    gateway_refund_id  text        UNIQUE,
    status             text        NOT NULL CHECK (status IN ('REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED')),
    gateway_version    bigint      NOT NULL DEFAULT 0 CHECK (gateway_version >= 0),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    -- The saga's refunds have its reason; the gateway's late-success refunds are LATE_SUCCESS; a duplicate success's
    -- refund has none. CASE and coalesce, not OR: a CHECK that evaluates to NULL passes.
    CONSTRAINT refunds_reason CHECK (
        CASE initiated_by
            WHEN 'MERCHANT' THEN reason IS NOT NULL
            WHEN 'SYSTEM_LATE_SUCCESS' THEN coalesce(reason = 'LATE_SUCCESS', false)
            ELSE reason IS NULL
        END),
    -- Requested until the gateway has it.
    CONSTRAINT refunds_requested CHECK ((status = 'REQUESTED') = (gateway_refund_id IS NULL))
);

-- One refund per order and reason from the saga (order lifecycle §5).
CREATE UNIQUE INDEX refunds_per_reason ON refunds (order_id, reason) WHERE initiated_by = 'MERCHANT';
