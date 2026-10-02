-- Tasks for the in-process TaskScheduler adapter (ADR-003, LLD §2.7).

CREATE TABLE scheduled_tasks (
    id              uuid        PRIMARY KEY,
    type            text        NOT NULL,
    dedupe_key      text,
    payload         text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD')),
    run_at          timestamptz NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL,
    every_seconds   bigint,
    lease_until     timestamptz,
    correlation_id  text,
    last_error      text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

COMMENT ON COLUMN scheduled_tasks.every_seconds IS 'Set for recurring tasks, which are rescheduled instead of finishing.';

CREATE UNIQUE INDEX scheduled_tasks_active_dedupe ON scheduled_tasks (dedupe_key)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX scheduled_tasks_due ON scheduled_tasks (run_at) WHERE status = 'PENDING';

CREATE INDEX scheduled_tasks_leased ON scheduled_tasks (lease_until) WHERE status = 'RUNNING';

CREATE INDEX scheduled_tasks_finished ON scheduled_tasks (updated_at) WHERE status IN ('SUCCEEDED', 'DEAD');
