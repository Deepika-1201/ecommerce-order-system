-- The outbox and processed-message records (ADR-008 as amended, LLD §2.2–2.4).
-- Flyway runs this with the platform schema as the default schema.

CREATE TABLE outbox (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id       uuid        NOT NULL,
    destination      text        NOT NULL,
    aggregate_type   text        NOT NULL,
    aggregate_id     text        NOT NULL,
    sequence         bigint      NOT NULL,
    type             text        NOT NULL,
    version          integer     NOT NULL,
    traceparent      text,
    envelope         text        NOT NULL,
    created_at       timestamptz NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL,
    last_error       text,
    parked_at        timestamptz,
    delivered_at     timestamptz,
    CONSTRAINT outbox_message_per_destination UNIQUE (destination, message_id)
);

COMMENT ON TABLE outbox IS
    'One row per message and destination (handler:<consumer> or kafka:<topic>); pending while delivered_at and parked_at are null.';

-- Due rows, in the order workers take them.
CREATE INDEX outbox_pending ON outbox (next_attempt_at, id)
    WHERE delivered_at IS NULL AND parked_at IS NULL;

-- The eligibility rule: is there an earlier undelivered row for this destination and aggregate?
CREATE INDEX outbox_pending_by_aggregate ON outbox (destination, aggregate_type, aggregate_id, sequence, id)
    WHERE delivered_at IS NULL;

CREATE INDEX outbox_delivered ON outbox (delivered_at)
    WHERE delivered_at IS NOT NULL;

CREATE TABLE processed_messages (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);

CREATE INDEX processed_messages_age ON processed_messages (processed_at);
