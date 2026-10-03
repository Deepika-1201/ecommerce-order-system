-- A gateway version is unknown until the gateway reports one: the gateway counts from 0, so 0 cannot also mean
-- "nothing applied yet" (LLD §7.6). NULL does.

ALTER TABLE payment_records ALTER COLUMN gateway_version DROP DEFAULT;
ALTER TABLE payment_records ALTER COLUMN gateway_version DROP NOT NULL;
UPDATE payment_records SET gateway_version = NULL WHERE status IS NULL;
ALTER TABLE payment_records ADD CONSTRAINT payment_records_version
    CHECK ((gateway_version IS NULL) = (status IS NULL));

ALTER TABLE refunds ALTER COLUMN gateway_version DROP DEFAULT;
ALTER TABLE refunds ALTER COLUMN gateway_version DROP NOT NULL;
UPDATE refunds SET gateway_version = NULL WHERE gateway_refund_id IS NULL;
ALTER TABLE refunds ADD CONSTRAINT refunds_version CHECK ((gateway_version IS NULL) = (gateway_refund_id IS NULL));
