-- ============================================================================
-- [Phase 23-4] 플래시 세일 부하 테스트 — 매 런 직전 리셋 스크립트
--
-- 실행:  psql -U postgres -d shopping_mall_loadtest_db -f reset-flash-sale.sql
-- 동작:
--   1) 직전 런이 만든 flash_sale_purchases 행 제거(uk_fsp_user_sale 해제)
--   2) 관련 주문(flash sale 경로로 만든 orders + order_items) 제거
--   3) remaining_quantity 를 allocated_quantity 로 복구
--   4) status=ACTIVE, end_time 을 +2h 로 갱신(연속 측정 안전 버퍼)
--
-- 주의: 이 스크립트는 LOADTEST_FLASH 라벨이 붙은 세일만 건드린다.
-- ============================================================================

-- 0) 대상 세일에서 만들어진 주문 ID 수집
CREATE TEMP TABLE _fs_order_ids ON COMMIT DROP AS
SELECT p.order_id
  FROM flash_sale_purchases p
  JOIN flash_sales s ON s.flash_sale_id = p.flash_sale_id
 WHERE s.title = 'LOADTEST_FLASH';

-- 1) 구매 로그 제거(같은 사용자 재참여 허용)
DELETE FROM flash_sale_purchases
 WHERE flash_sale_id IN (SELECT flash_sale_id FROM flash_sales WHERE title = 'LOADTEST_FLASH');

-- 2) 해당 세일에서 발생한 주문(라인 → 주문) 제거
DELETE FROM order_items WHERE order_id IN (SELECT order_id FROM _fs_order_ids);
DELETE FROM orders WHERE order_id IN (SELECT order_id FROM _fs_order_ids);

-- 3) 재고 복원 + 상태/창 재설정
UPDATE flash_sale_items
   SET remaining_quantity = allocated_quantity,
       version = version + 1
 WHERE flash_sale_id IN (SELECT flash_sale_id FROM flash_sales WHERE title = 'LOADTEST_FLASH');

UPDATE flash_sales
   SET status = 'ACTIVE',
       start_time = NOW() - INTERVAL '5 minutes',
       end_time = NOW() + INTERVAL '2 hours'
 WHERE title = 'LOADTEST_FLASH';

-- 4) 멱등성 키 캐시 정리(재시도 키 충돌 방지). 부하 테스트 한정 안전한 정리.
DELETE FROM idempotency_records WHERE resource_type = 'FLASH_SALE';

-- 5) 결과
SELECT s.flash_sale_id, s.title, s.status, s.end_time,
       i.flash_sale_item_id, i.allocated_quantity, i.remaining_quantity
  FROM flash_sales s
  JOIN flash_sale_items i ON i.flash_sale_id = s.flash_sale_id
 WHERE s.title = 'LOADTEST_FLASH';
