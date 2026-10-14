CREATE TABLE availability_hints (
    sku varchar(255) NOT NULL,
    location_code varchar(255) NOT NULL,
    available bigint NOT NULL CHECK (available >= 0),
    sequence bigint NOT NULL CHECK (sequence >= 0),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (sku, location_code)
);