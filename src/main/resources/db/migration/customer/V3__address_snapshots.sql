-- Immutable copies of the addresses orders were placed with (ADR-023, LLD §6.3). Rows are never updated.

CREATE TABLE address_snapshots (
    id             uuid        PRIMARY KEY,
    customer_id    uuid        NOT NULL REFERENCES customers (id),
    recipient_name text        NOT NULL,
    phone          text        NOT NULL,
    line1          text        NOT NULL,
    line2          text,
    landmark       text,
    city           text        NOT NULL,
    state_code     text        NOT NULL CHECK (state_code ~ '^[0-9]{2}$'),
    pin_code       text        NOT NULL CHECK (pin_code ~ '^[1-9][0-9]{5}$'),
    created_at     timestamptz NOT NULL
);

-- Account deletion (phase 13) anonymizes a customer's snapshots with their addresses.
CREATE INDEX address_snapshots_by_customer ON address_snapshots (customer_id);
