\echo '=== AFTER: yearly spent aggregation ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id, COALESCE(SUM(final_amount), 0)
FROM orders
WHERE order_status <> 'CANCELLED'
  AND order_date >= :'start_date'::timestamp
  AND order_date < :'end_date'::timestamp
GROUP BY user_id;

\echo '=== AFTER: pending return count via literal native query ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*)
FROM order_items
WHERE status = 'RETURN_REQUESTED';
