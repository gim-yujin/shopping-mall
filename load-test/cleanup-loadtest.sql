-- ============================================================================
-- 부하 테스트 후 데이터 정리 스크립트
-- 실행: psql -U postgres -d shopping_mall_db -f cleanup-loadtest.sql
-- ============================================================================

-- 1) 테스트 사용자의 주문 관련 데이터 삭제
DELETE FROM order_items WHERE order_id IN (
    SELECT order_id FROM orders WHERE user_id IN (
        SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
    )
);
DELETE FROM orders WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);

-- 2) 장바구니, 위시리스트, 리뷰 삭제
DELETE FROM carts WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);
DELETE FROM wishlists WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);
DELETE FROM review_helpful WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);
DELETE FROM reviews WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);

-- 3) 테스트용 쿠폰 관련
DELETE FROM user_coupons WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);
UPDATE coupons SET used_quantity = 0 WHERE coupon_code = 'LOADTEST_RUSH';

-- 4) 검색 로그 정리
DELETE FROM search_logs WHERE user_id IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);

-- 5) 재고 이력 정리
DELETE FROM product_inventory_history WHERE created_by IN (
    SELECT user_id FROM users WHERE username LIKE 'loaduser_%'
);

-- 6) 상품 재고 원복 (주문으로 차감된 재고 확인)
-- 필요 시 수동으로 확인:
-- SELECT product_id, product_name, stock_quantity FROM products WHERE stock_quantity < 10;

-- 7) 테스트 사용자 삭제 (선택사항 — 반복 테스트 시 유지)
-- DELETE FROM users WHERE username LIKE 'loaduser_%';

-- 확인
SELECT 'load_test_orders' AS item, COUNT(*) AS count
FROM orders WHERE user_id IN (SELECT user_id FROM users WHERE username LIKE 'loaduser_%')
UNION ALL
SELECT 'load_test_carts', COUNT(*)
FROM carts WHERE user_id IN (SELECT user_id FROM users WHERE username LIKE 'loaduser_%')
UNION ALL
SELECT 'rush_coupon_used', used_quantity
FROM coupons WHERE coupon_code = 'LOADTEST_RUSH';
