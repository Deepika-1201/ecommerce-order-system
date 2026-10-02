-- The append-only audit log (LLD §2.9).

CREATE TABLE audit_log (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     timestamptz NOT NULL,
    actor_type      text        NOT NULL,
    actor_id        text        NOT NULL,
    action          text        NOT NULL,
    target_type     text        NOT NULL,
    target_id       text        NOT NULL,
    reason          text,
    details         jsonb       NOT NULL,
    request_id      text,
    correlation_id  text
);

CREATE INDEX audit_log_target ON audit_log (target_type, target_id, occurred_at);

CREATE FUNCTION reject_audit_log_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_change();
