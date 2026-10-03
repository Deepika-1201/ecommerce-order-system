-- Carts for customers and guests (LLD §4.3, §4.4). A merged or expired cart is deleted, so there is no status.

CREATE TABLE carts (
    id               uuid        PRIMARY KEY,
    customer_id      uuid,
    guest_token_hash bytea,
    coupon_code      text,
    version          bigint      NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    CONSTRAINT carts_one_owner CHECK ((customer_id IS NULL) <> (guest_token_hash IS NULL)),
    CONSTRAINT carts_customer_unique UNIQUE (customer_id),
    CONSTRAINT carts_guest_token_unique UNIQUE (guest_token_hash)
);

CREATE INDEX carts_by_expiry ON carts (expires_at);

CREATE TABLE cart_lines (
    cart_id           uuid        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    sku               text        NOT NULL,
    quantity          integer     NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    added_price_paise bigint      NOT NULL,
    added_at          timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    PRIMARY KEY (cart_id, sku)
);
