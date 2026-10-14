CREATE TABLE order_progress (
    order_id uuid PRIMARY KEY,
    last_sequence bigint NOT NULL CHECK (last_sequence >= 0)
);

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    kind varchar(64) NOT NULL,
    subject varchar(200) NOT NULL,
    body text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (order_id, kind),
    CHECK ((status = 'PENDING' AND finished_at IS NULL)
        OR (status <> 'PENDING' AND finished_at IS NOT NULL))
);

CREATE INDEX notifications_pending ON notifications (created_at) WHERE status = 'PENDING';
CREATE INDEX notifications_failed ON notifications (updated_at) WHERE status = 'FAILED';