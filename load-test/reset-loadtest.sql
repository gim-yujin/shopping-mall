-- ============================================================================
-- Shopping Mall 부하 테스트 — 사전 준비 SQL (v4)
-- ============================================================================
-- 테스트 전에 실행하여 이전 테스트 잔여 데이터를 정리합니다.
-- ✅ v4 추가: "재고 부족은 의도되지 않음"을 전제로, 부하 테스트용 상품 재고를 충분히 크게 리셋합니다.
--
-- 실행 방법:
--   sudo -u postgres psql -d shopping_mall_db -f reset-loadtest.v4.sql
-- ============================================================================

BEGIN;

-- 1) 쿠폰 발급 내역 초기화
DELETE FROM user_coupons
WHERE coupon_id = (SELECT coupon_id FROM coupons WHERE coupon_code = 'LOADTEST_RUSH');

UPDATE coupons
SET used_quantity = 0
WHERE coupon_code = 'LOADTEST_RUSH';

-- 2) 장바구니 비우기
DELETE FROM carts
WHERE user_id IN (SELECT user_id FROM users WHERE username LIKE 'loaduser_%');

-- 3) (v4) 주문 테스트용 상품 재고 리셋
--    ⚠ 아래 product_id 목록은 load-test.v4.js의 DEFAULT_PRODUCT_IDS와 동일합니다.
--    환경에 맞게 목록/수량을 조정하세요.
UPDATE products
SET stock_quantity = GREATEST(stock_quantity, 100000)
WHERE product_id IN (25, 26, 27, 30, 31, 33, 34, 35, 36, 37)
  AND is_active = true;

-- 4) 확인
SELECT coupon_code, total_quantity, used_quantity
FROM coupons WHERE coupon_code = 'LOADTEST_RUSH';

SELECT COUNT(*) AS remaining_carts
FROM carts WHERE user_id IN (SELECT user_id FROM users WHERE username LIKE 'loaduser_%');

SELECT product_id, stock_quantity
FROM products
WHERE product_id IN (25, 26, 27, 30, 31, 33, 34, 35, 36, 37)
ORDER BY product_id;

COMMIT;

-- ============================================================================
-- 결과 예상:
--   - 쿠폰 used_quantity: 0
--   - remaining_carts: 0
--   - 지정 상품 stock_quantity: 최소 100000 이상
-- ============================================================================
