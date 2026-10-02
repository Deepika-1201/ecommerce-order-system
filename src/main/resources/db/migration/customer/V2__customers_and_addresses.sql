-- Customer profiles and addresses (LLD §3.4). Personal data lives only in this schema.

CREATE TABLE customers (
    id          uuid        PRIMARY KEY,
    subject     text        NOT NULL UNIQUE,
    email       text,
    name        text,
    phone       text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

COMMENT ON COLUMN customers.subject IS 'The identity provider''s user id (the token''s sub claim).';

CREATE TABLE addresses (
    id              uuid        PRIMARY KEY,
    customer_id     uuid        NOT NULL REFERENCES customers (id),
    recipient_name  text        NOT NULL,
    phone           text        NOT NULL,
    line1           text        NOT NULL,
    line2           text,
    landmark        text,
    city            text        NOT NULL,
    state_code      text        NOT NULL CHECK (state_code ~ '^[0-9]{2}$'),
    pin_code        text        NOT NULL CHECK (pin_code ~ '^[1-9][0-9]{5}$'),
    is_default      boolean     NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

COMMENT ON COLUMN addresses.state_code IS 'GST state code, such as 29 for Karnataka.';

-- At most one default per customer; the service keeps exactly one while any address exists.
CREATE UNIQUE INDEX addresses_one_default ON addresses (customer_id) WHERE is_default;

CREATE INDEX addresses_by_customer ON addresses (customer_id, created_at);
