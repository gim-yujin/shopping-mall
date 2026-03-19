-- [Phase 18] 주문 목록 읽기 전용 뷰 — CQRS 읽기 모델 분리.
--
-- 기존 OrderQueryService는 Page<Order> 조회 후 fetchOrderItems()로 2차 쿼리를 발행하여
-- 아이템 수를 계산했다. 이 뷰는 서브쿼리로 item_count와 first_product_name을 미리 계산하여
-- 단일 쿼리로 주문 목록 데이터를 제공한다.

CREATE OR REPLACE VIEW v_order_list AS
SELECT
    o.order_id,
    o.order_number,
    o.user_id,
    o.order_status,
    o.total_amount,
    o.discount_amount,
    o.shipping_fee,
    o.final_amount,
    o.order_date,
    o.paid_at,
    o.shipped_at,
    o.delivered_at,
    o.cancelled_at,
    (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count,
    (SELECT oi2.product_name FROM order_items oi2
     WHERE oi2.order_id = o.order_id
     ORDER BY oi2.order_item_id LIMIT 1) AS first_product_name
FROM orders o;
