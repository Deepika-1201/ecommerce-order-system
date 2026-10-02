-- API idempotency keys (ADR-010, LLD §2.8). A row without a response belongs to a request still in its transaction.

CREATE TABLE idempotency_keys (
    scope              text        NOT NULL,
    key                text        NOT NULL,
    fingerprint        text        NOT NULL,
    response_status    integer,
    response_body      text,
    response_location  text,
    created_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    PRIMARY KEY (scope, key)
);

CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);
