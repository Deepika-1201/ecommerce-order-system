-- The order's refund also ends: succeeded or failed (LLD §7.9).

ALTER TABLE orders DROP CONSTRAINT orders_refund_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_refund_status_check
    CHECK (refund_status IN ('REQUESTED', 'INITIATED', 'SUCCEEDED', 'FAILED'));
