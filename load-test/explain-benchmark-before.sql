\echo '=== Benchmark Size ==='
SELECT 'orders=' || COUNT(*) FROM orders;
SELECT 'order_items=' || COUNT(*) FROM order_items;
SELECT 'return_requested=' || COUNT(*) FROM order_items WHERE status = 'RETURN_REQUESTED';

\echo '=== BEFORE: yearly spent aggregation ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id, COALESCE(SUM(final_amount), 0)
FROM orders
WHERE order_status <> 'CANCELLED'
  AND order_date >= :'start_date'::timestamp
  AND order_date < :'end_date'::timestamp
GROUP BY user_id;

\echo '=== BEFORE: pending return count via generic prepared plan ==='
SET plan_cache_mode = force_generic_plan;
PREPARE count_status(varchar) AS
    SELECT COUNT(*) FROM order_items WHERE status = $1;
EXPLAIN (ANALYZE, BUFFERS)
EXECUTE count_status('RETURN_REQUESTED');
DEALLOCATE count_status;
RESET plan_cache_mode;
