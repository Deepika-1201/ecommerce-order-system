-- Shipments, their tracking, and the carrier simulator's parcels (LLD §8.4, §8.9), which replace the phase 6
-- simulator (ADR-022, ADR-025).

DROP TABLE simulated_shipments;

CREATE TABLE shipments (
    id                   uuid        PRIMARY KEY,
    order_id             uuid        NOT NULL UNIQUE,
    delivery_address_id  uuid,
    status               text        NOT NULL CHECK (status IN ('PENDING_BOOKING', 'BOOKING_FAILED', 'BOOKED', 'PACKED',
                                                                'HANDED_OVER', 'IN_TRANSIT', 'OUT_FOR_DELIVERY',
                                                                'DELIVERY_ATTEMPT_FAILED', 'DELIVERED', 'RTO_IN_TRANSIT',
                                                                'RTO_DELIVERED', 'CANCELLED')),
    carrier              text,
    awb                  text        UNIQUE,
    booking_deadline     timestamptz,
    booking_failure      text,
    last_scan_at         timestamptz,
    cancel_requested     boolean     NOT NULL DEFAULT false,
    version              bigint      NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    -- Only a cancellation that arrived before the create leaves a shipment without an address.
    CONSTRAINT shipments_address CHECK (delivery_address_id IS NOT NULL OR status = 'CANCELLED'),
    -- The carrier's AWB from the booking on; a cancelled shipment may or may not have been booked.
    CONSTRAINT shipments_awb CHECK (
        CASE status
            WHEN 'PENDING_BOOKING' THEN awb IS NULL
            WHEN 'BOOKING_FAILED' THEN awb IS NULL
            WHEN 'CANCELLED' THEN true
            ELSE awb IS NOT NULL
        END),
    CONSTRAINT shipments_carrier CHECK ((carrier IS NULL) = (awb IS NULL)),
    CONSTRAINT shipments_booking_deadline CHECK (status <> 'PENDING_BOOKING' OR booking_deadline IS NOT NULL),
    CONSTRAINT shipments_booking_failure CHECK ((status = 'BOOKING_FAILED') = (booking_failure IS NOT NULL)),
    CONSTRAINT shipments_cancel_requested CHECK (NOT cancel_requested OR status IN ('BOOKED', 'PACKED'))
);

-- The warehouse's lists (LLD §8.7): oldest first within a status.
CREATE INDEX shipments_to_work_on ON shipments (status, id) WHERE status IN ('BOOKED', 'PACKED');

CREATE TABLE shipment_lines (
    shipment_id  uuid     NOT NULL REFERENCES shipments (id),
    sku          text     NOT NULL,
    quantity     integer  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (shipment_id, sku)
);

CREATE TABLE tracking_events (
    id           uuid        PRIMARY KEY,
    shipment_id  uuid        NOT NULL REFERENCES shipments (id),
    source       text        NOT NULL CHECK (source IN ('CARRIER', 'WAREHOUSE')),
    event_id     text        UNIQUE,
    status       text        NOT NULL CHECK (status IN ('PACKED', 'HANDED_OVER', 'IN_TRANSIT', 'OUT_FOR_DELIVERY',
                                                        'DELIVERY_ATTEMPT_FAILED', 'DELIVERED', 'RTO_IN_TRANSIT',
                                                        'RTO_DELIVERED')),
    location     text,
    occurred_at  timestamptz NOT NULL,
    received_at  timestamptz NOT NULL,
    applied      boolean     NOT NULL,
    -- A carrier scan has the carrier's event id; a warehouse mark has none, and always applies.
    CONSTRAINT tracking_events_source CHECK (
        CASE source
            WHEN 'CARRIER' THEN event_id IS NOT NULL
            ELSE event_id IS NULL AND applied AND status IN ('PACKED', 'HANDED_OVER')
        END)
);

CREATE INDEX tracking_events_of_shipment ON tracking_events (shipment_id, occurred_at);

-- The carrier simulator's own records (ADR-025): what a carrier would keep on its side. The newest scan is where the
-- parcel is; late scans the scenario sends afterwards do not change it.
CREATE TABLE simulated_parcels (
    reference         uuid        PRIMARY KEY,
    awb               text        UNIQUE,
    pin_code          text        NOT NULL,
    booking_attempts  integer     NOT NULL CHECK (booking_attempts > 0),
    cancelled         boolean     NOT NULL DEFAULT false,
    scan              text        CHECK (scan IN ('picked_up', 'in_transit', 'out_for_delivery',
                                                  'delivery_attempt_failed', 'delivered', 'rto_in_transit',
                                                  'rto_delivered')),
    scanned_at        timestamptz,
    next_step         integer     NOT NULL DEFAULT 0 CHECK (next_step >= 0),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT simulated_parcels_scanned CHECK ((scan IS NULL) = (scanned_at IS NULL)),
    CONSTRAINT simulated_parcels_cancelled CHECK (NOT cancelled OR (awb IS NOT NULL AND scan IS NULL))
);
