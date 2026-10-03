-- Verified webhooks, stored before they are acknowledged (LLD §7.7). The body is text, byte for byte: jsonb would
-- normalize it, and a signed body must stay verifiable.

CREATE TABLE webhook_inbox (
    source        text        NOT NULL,
    event_id      text        NOT NULL,
    type          text        NOT NULL,
    body          text        NOT NULL,
    received_at   timestamptz NOT NULL,
    processed_at  timestamptz,
    PRIMARY KEY (source, event_id)
);

CREATE INDEX webhook_inbox_processed ON webhook_inbox (processed_at) WHERE processed_at IS NOT NULL;
