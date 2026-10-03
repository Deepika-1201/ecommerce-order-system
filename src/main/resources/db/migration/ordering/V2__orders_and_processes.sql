-- Orders and their processes (LLD §6.4, §6.5, §6.10). An order's row lock guards the order and its process.

-- For display only (EC and 9 digits): an order is always found by id and owner.
CREATE SEQUENCE order_numbers AS bigint MINVALUE 1 MAXVALUE 999999999 NO CYCLE;

CREATE TABLE orders (
    id                    uuid        PRIMARY KEY,
    number                text        NOT NULL,
    customer_id           uuid        NOT NULL,
    quote_id              uuid        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('PLACED', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPED',
                                                                 'DELIVERED', 'DELIVERY_FAILED', 'RETURNED_TO_ORIGIN',
                                                                 'CANCELLING', 'CANCELLED', 'REJECTED')),
    reason                text,
    short_sku             text,
    tax_regime            text        NOT NULL CHECK (tax_regime IN ('INTRA_STATE', 'INTER_STATE')),
    supply_state_code     text        NOT NULL,
    delivery_state_code   text        NOT NULL,
    coupon_id             uuid,
    coupon_code           text,
    gross_paise           bigint      NOT NULL,
    discount_paise        bigint      NOT NULL,
    goods_paise           bigint      NOT NULL,
    shipping_paise        bigint      NOT NULL,
    shipping_gst_rate_bps integer     NOT NULL,
    shipping_cgst_paise   bigint      NOT NULL,
    shipping_sgst_paise   bigint      NOT NULL,
    shipping_igst_paise   bigint      NOT NULL,
    taxable_value_paise   bigint      NOT NULL,
    cgst_paise            bigint      NOT NULL,
    sgst_paise            bigint      NOT NULL,
    igst_paise            bigint      NOT NULL,
    grand_total_paise     bigint      NOT NULL,
    delivery_address_id   uuid        NOT NULL,
    billing_address_id    uuid        NOT NULL,
    payment_id            uuid,
    checkout_url          text,
    refund_amount_paise   bigint      CHECK (refund_amount_paise >= 0),
    refund_status         text        CHECK (refund_status IN ('REQUESTED', 'INITIATED')),
    version               bigint      NOT NULL,
    placed_at             timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT orders_number_unique UNIQUE (number),
    -- One order per quote (LLD §6.3).
    CONSTRAINT orders_quote_unique UNIQUE (quote_id),
    CONSTRAINT orders_reason CHECK (
        (status = 'REJECTED' AND reason IN ('OUT_OF_STOCK', 'COUPON_UNAVAILABLE', 'PAYMENTS_UNAVAILABLE'))
        OR (status = 'CANCELLED' AND reason IN ('CUSTOMER', 'SUPPORT', 'PAYMENT_FAILED', 'PAYMENT_EXPIRED',
                                                'STOCK_LOST_AFTER_PAYMENT'))
        OR (status NOT IN ('REJECTED', 'CANCELLED') AND reason IS NULL)),
    CONSTRAINT orders_short_sku CHECK (coalesce(reason = 'OUT_OF_STOCK', false) = (short_sku IS NOT NULL)),
    CONSTRAINT orders_coupon CHECK ((coupon_id IS NULL) = (coupon_code IS NULL)),
    CONSTRAINT orders_refund CHECK ((refund_amount_paise IS NULL) = (refund_status IS NULL)),
    CONSTRAINT orders_add_up CHECK (
        goods_paise = gross_paise - discount_paise
        AND grand_total_paise = goods_paise + shipping_paise
        AND grand_total_paise = taxable_value_paise + cgst_paise + sgst_paise + igst_paise)
);

-- A customer's orders, newest first: ids are UUIDv7, so they sort by creation.
CREATE INDEX orders_by_customer ON orders (customer_id, id);

-- option_values is an array of {name, value}, as in pricing.quote_lines.
CREATE TABLE order_lines (
    order_id            uuid    NOT NULL REFERENCES orders (id),
    line_no             integer NOT NULL,
    sku                 text    NOT NULL,
    product_id          uuid    NOT NULL,
    variant_id          uuid    NOT NULL,
    title               text    NOT NULL,
    option_values       jsonb   NOT NULL,
    image_key           text,
    quantity            integer NOT NULL CHECK (quantity >= 1),
    unit_price_paise    bigint  NOT NULL,
    gross_paise         bigint  NOT NULL,
    discount_paise      bigint  NOT NULL,
    amount_paise        bigint  NOT NULL,
    taxable_value_paise bigint  NOT NULL,
    gst_rate_bps        integer NOT NULL,
    cgst_paise          bigint  NOT NULL,
    sgst_paise          bigint  NOT NULL,
    igst_paise          bigint  NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT order_lines_add_up CHECK (
        amount_paise = gross_paise - discount_paise
        AND taxable_value_paise + cgst_paise + sgst_paise + igst_paise = amount_paise)
);

CREATE TABLE order_processes (
    order_id            uuid        PRIMARY KEY REFERENCES orders (id),
    step                text        NOT NULL CHECK (step IN ('RESERVING_STOCK', 'RESERVING_COUPON', 'CREATING_PAYMENT',
                                                             'AWAITING_PAYMENT', 'COMMITTING_STOCK',
                                                             'AWAITING_HANDOVER', 'AWAITING_DELIVERY',
                                                             'AWAITING_RETURN', 'CANCELLING_PAYMENT',
                                                             'AWAITING_PAYMENT_OUTCOME', 'CANCELLING_SHIPMENT',
                                                             'REFUNDING', 'DONE')),
    cancel_reason       text        CHECK (cancel_reason IN ('CUSTOMER', 'SUPPORT')),
    cancel_code         text        CHECK (cancel_code IN ('CUSTOMER_REQUEST', 'SUSPECTED_FRAUD', 'ITEM_UNAVAILABLE',
                                                           'PRICING_ERROR', 'OTHER')),
    cancel_note         text,
    cancel_requested_at timestamptz,
    payment_succeeded   boolean     NOT NULL,
    hold_expires_at     timestamptz,
    deadline_at         timestamptz,
    attempts            integer     NOT NULL CHECK (attempts >= 0),
    refund_reason       text        CHECK (refund_reason IN ('ORDER_CANCELLED', 'STOCK_LOST_AFTER_PAYMENT',
                                                             'RETURNED_TO_ORIGIN', 'LATE_SUCCESS')),
    version             bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT order_processes_deadline CHECK ((step = 'DONE') = (deadline_at IS NULL)),
    CONSTRAINT order_processes_cancellation CHECK (
        (cancel_reason IS NULL AND cancel_code IS NULL AND cancel_note IS NULL AND cancel_requested_at IS NULL)
        OR (cancel_reason = 'CUSTOMER' AND cancel_code IS NULL AND cancel_note IS NULL
            AND cancel_requested_at IS NOT NULL)
        OR (cancel_reason = 'SUPPORT' AND cancel_code IS NOT NULL AND cancel_requested_at IS NOT NULL
            AND (cancel_code <> 'OTHER' OR cancel_note IS NOT NULL)))
);

-- The deadline sweep reads only processes that still wait for something (LLD §6.7).
CREATE INDEX order_processes_due ON order_processes (deadline_at) WHERE step <> 'DONE';
