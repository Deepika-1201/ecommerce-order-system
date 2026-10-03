-- The shipment simulator, until phase 8 replaces it with shipments and the carrier (ADR-022, LLD §6.8).

CREATE TABLE simulated_shipments (
    order_id   uuid        PRIMARY KEY,
    status     text        NOT NULL CHECK (status IN ('BOOKED', 'HANDED_OVER', 'DELIVERED', 'RETURNING', 'RETURNED',
                                                      'CANCELLED')),
    version    bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
