-- Stock per SKU and location, reservations and stock movements (LLD §5.3-§5.10, ADR-009, ADR-021).

CREATE TABLE locations (
    code       text        PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL
);

INSERT INTO locations (code, name, created_at) VALUES ('BLR1', 'Bengaluru warehouse', now());

-- available = on_hand - reserved. The counters change only by conditional updates (LLD §5.3).
CREATE TABLE stock_items (
    sku           text        NOT NULL,
    location_code text        NOT NULL REFERENCES locations (code),
    on_hand       bigint      NOT NULL,
    reserved      bigint      NOT NULL,
    version       bigint      NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (sku, location_code),
    CONSTRAINT stock_items_counters CHECK (0 <= reserved AND reserved <= on_hand)
);

CREATE TABLE reservations (
    id              uuid        PRIMARY KEY,
    order_id        uuid        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('HELD', 'REJECTED', 'COMMITTED', 'RELEASED', 'EXPIRED',
                                                           'FULFILLED', 'RETURNED')),
    expires_at      timestamptz,
    short_sku       text,
    short_available bigint,
    version         bigint      NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT reservations_order_unique UNIQUE (order_id),
    CONSTRAINT reservations_held_until CHECK (status <> 'HELD' OR expires_at IS NOT NULL),
    CONSTRAINT reservations_shortage CHECK (
        (status = 'REJECTED' AND short_sku IS NOT NULL AND short_available IS NOT NULL AND short_available >= 0)
        OR (status <> 'REJECTED' AND short_sku IS NULL AND short_available IS NULL))
);

-- Expiry reads only the holds waiting to expire, never the history (LLD §5.6).
CREATE INDEX reservations_expiring ON reservations (expires_at) WHERE status = 'HELD';

-- No foreign key to stock_items: a rejected reservation can name a SKU that has no stock item.
CREATE TABLE reservation_lines (
    reservation_id uuid    NOT NULL REFERENCES reservations (id),
    sku            text    NOT NULL,
    location_code  text    NOT NULL REFERENCES locations (code),
    quantity       integer NOT NULL CHECK (quantity >= 1),
    PRIMARY KEY (reservation_id, sku, location_code)
);

-- The ledger of on_hand (ADR-021). Ids are taken under the stock row's lock, so a SKU's movements sort in the order
-- they were applied, whichever instance applied them.
CREATE TABLE stock_movements (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku           text        NOT NULL,
    location_code text        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('RECEIPT', 'ADJUSTMENT', 'HANDOVER', 'RETURN')),
    quantity      integer     NOT NULL,
    reason        text        CHECK (reason IN ('DAMAGED', 'LOST', 'FOUND', 'COUNT_CORRECTION')),
    note          text,
    reference     text,
    order_id      uuid,
    on_hand_after bigint      NOT NULL CHECK (on_hand_after >= 0),
    actor_id      text,
    created_at    timestamptz NOT NULL,
    FOREIGN KEY (sku, location_code) REFERENCES stock_items (sku, location_code),
    CONSTRAINT stock_movements_sign CHECK (CASE
        WHEN kind IN ('RECEIPT', 'RETURN') THEN quantity > 0
        WHEN kind = 'HANDOVER' THEN quantity < 0
        WHEN reason IN ('DAMAGED', 'LOST') THEN quantity < 0
        WHEN reason = 'FOUND' THEN quantity > 0
        ELSE quantity <> 0
    END),
    CONSTRAINT stock_movements_reason CHECK ((kind = 'ADJUSTMENT') = (reason IS NOT NULL)),
    CONSTRAINT stock_movements_order CHECK ((kind IN ('HANDOVER', 'RETURN')) = (order_id IS NOT NULL))
);

-- Even a broken status guard cannot hand an order's line over, or take its return, twice.
CREATE UNIQUE INDEX stock_movements_once_per_order ON stock_movements (order_id, kind, sku, location_code)
    WHERE order_id IS NOT NULL;

CREATE INDEX stock_movements_history ON stock_movements (sku, location_code, id);

CREATE FUNCTION reject_stock_movement_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'stock_movements is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER stock_movements_append_only
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_change();
