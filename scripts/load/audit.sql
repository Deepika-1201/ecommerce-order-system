BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH target_orders AS (
    SELECT DISTINCT orders.id, orders.status, orders.reason, orders.short_sku
    FROM ordering.orders orders JOIN ordering.order_lines lines ON lines.order_id = orders.id
    WHERE lines.sku = :'sku'
), stock AS (
    SELECT count(*) AS rows, coalesce(sum(on_hand), 0) AS on_hand,
           coalesce(sum(reserved), 0) AS reserved,
           coalesce(sum(on_hand - reserved), 0) AS available,
           coalesce(bool_and(reserved >= 0 AND on_hand >= reserved), false) AS counters_valid
    FROM inventory.stock_items WHERE sku = :'sku'
), active AS (
    SELECT reservation.order_id, sum(line.quantity) AS units
    FROM inventory.reservations reservation
    JOIN inventory.reservation_lines line ON line.reservation_id = reservation.id
    WHERE line.sku = :'sku' AND reservation.status IN ('HELD', 'COMMITTED')
    GROUP BY reservation.order_id
), movements AS (
    SELECT coalesce(sum(quantity), 0) AS units FROM inventory.stock_movements WHERE sku = :'sku'
), replays AS (
    SELECT response_status, response_body::jsonb ->> 'id' AS order_id
    FROM platform.idempotency_keys WHERE key LIKE 'load-order-' || :'run_id' || '-%'
)
SELECT json_build_object(
    'schema_version', 1,
    'run_id', :'run_id',
    'sku', :'sku',
    'stock_rows', (SELECT rows FROM stock),
    'on_hand', (SELECT on_hand FROM stock),
    'reserved', (SELECT reserved FROM stock),
    'available', (SELECT available FROM stock),
    'counters_valid', (SELECT counters_valid FROM stock),
    'movement_units', (SELECT units FROM movements),
    'orders', (SELECT count(*) FROM target_orders),
    'payment_ready', (SELECT count(*) FROM target_orders WHERE status = 'AWAITING_PAYMENT'),
    'stock_rejected', (SELECT count(*) FROM target_orders
                       WHERE status = 'REJECTED' AND reason = 'OUT_OF_STOCK' AND short_sku = :'sku'),
    'active_reservations', (SELECT count(*) FROM active),
    'reserved_units', (SELECT coalesce(sum(units), 0) FROM active),
    'reservation_links_valid', NOT EXISTS (
        SELECT 1 FROM active LEFT JOIN target_orders orders ON orders.id = active.order_id
        WHERE orders.id IS NULL OR orders.status <> 'AWAITING_PAYMENT' OR active.units <> 1),
    'replay_rows', (SELECT count(*) FROM replays),
    'distinct_replay_orders', (SELECT count(DISTINCT order_id) FROM replays),
    'replay_links_valid', NOT EXISTS (
        SELECT 1 FROM replays LEFT JOIN target_orders orders ON orders.id::text = replays.order_id
        WHERE orders.id IS NULL OR replays.response_status <> 202),
    'catalog_products', (SELECT count(*) FROM catalog.products),
    'captured_at', now()
);

COMMIT;