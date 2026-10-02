-- Coupons and their redemptions (LLD §4.9, §4.10) and quotes (LLD §4.5).

CREATE TABLE coupons (
    id                  uuid        PRIMARY KEY,
    code                text        NOT NULL,
    kind                text        NOT NULL CHECK (kind IN ('PERCENT', 'FLAT')),
    percent_bps         integer,
    max_discount_paise  bigint,
    amount_paise        bigint,
    min_order_paise     bigint      NOT NULL CHECK (min_order_paise >= 0),
    valid_from          timestamptz NOT NULL,
    valid_until         timestamptz,
    total_limit         integer     CHECK (total_limit >= 1),
    per_customer_limit  integer     CHECK (per_customer_limit >= 1),
    active              boolean     NOT NULL,
    reserved            integer     NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    redeemed            integer     NOT NULL DEFAULT 0 CHECK (redeemed >= 0),
    version             bigint      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT coupons_code_unique UNIQUE (code),
    CONSTRAINT coupons_rule CHECK (
        (kind = 'PERCENT' AND percent_bps BETWEEN 1 AND 10000 AND amount_paise IS NULL
            AND (max_discount_paise IS NULL OR max_discount_paise >= 1))
        OR (kind = 'FLAT' AND amount_paise >= 1 AND percent_bps IS NULL AND max_discount_paise IS NULL)),
    CONSTRAINT coupons_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT coupons_within_limit CHECK (total_limit IS NULL OR reserved + redeemed <= total_limit)
);

CREATE TABLE coupon_redemptions (
    id          uuid        PRIMARY KEY,
    coupon_id   uuid        NOT NULL REFERENCES coupons (id),
    order_id    uuid        NOT NULL,
    customer_id uuid,
    status      text        NOT NULL CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT coupon_redemptions_order_unique UNIQUE (order_id)
);

CREATE INDEX coupon_redemptions_in_use ON coupon_redemptions (coupon_id, customer_id)
    WHERE status IN ('HELD', 'COMMITTED');

CREATE TABLE quotes (
    id                    uuid        PRIMARY KEY,
    cart_id               uuid        NOT NULL,
    customer_id           uuid,
    coupon_id             uuid,
    coupon_code           text,
    supply_state_code     text        NOT NULL,
    delivery_state_code   text        NOT NULL,
    tax_regime            text        NOT NULL CHECK (tax_regime IN ('INTRA_STATE', 'INTER_STATE')),
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
    valid_until           timestamptz NOT NULL,
    created_at            timestamptz NOT NULL,
    CONSTRAINT quotes_add_up CHECK (
        goods_paise = gross_paise - discount_paise
        AND grand_total_paise = goods_paise + shipping_paise
        AND grand_total_paise = taxable_value_paise + cgst_paise + sgst_paise + igst_paise)
);

CREATE INDEX quotes_by_validity ON quotes (valid_until);

-- option_values is an array of {name, value}: jsonb keeps array order, not key order.
CREATE TABLE quote_lines (
    quote_id                  uuid    NOT NULL REFERENCES quotes (id) ON DELETE CASCADE,
    line_no                   integer NOT NULL,
    sku                       text    NOT NULL,
    product_id                uuid    NOT NULL,
    variant_id                uuid    NOT NULL,
    title                     text    NOT NULL,
    option_values             jsonb   NOT NULL,
    image_key                 text,
    quantity                  integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    unit_price_paise          bigint  NOT NULL,
    previous_unit_price_paise bigint,
    gross_paise               bigint  NOT NULL,
    discount_paise            bigint  NOT NULL,
    amount_paise              bigint  NOT NULL,
    taxable_value_paise       bigint  NOT NULL,
    gst_rate_bps              integer NOT NULL,
    cgst_paise                bigint  NOT NULL,
    sgst_paise                bigint  NOT NULL,
    igst_paise                bigint  NOT NULL,
    PRIMARY KEY (quote_id, line_no),
    CONSTRAINT quote_lines_add_up CHECK (
        amount_paise = gross_paise - discount_paise
        AND taxable_value_paise + cgst_paise + sgst_paise + igst_paise = amount_paise)
);
