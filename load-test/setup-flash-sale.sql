-- ============================================================================
-- [Phase 23-4] 플래시 세일 부하 테스트용 시드 스크립트
--
-- 실행:  psql -U postgres -d shopping_mall_loadtest_db -f setup-flash-sale.sql
-- 전제:  setup-loadtest.sql 1회 선실행(loaduser_001 ~ 200 존재)
--        V23__add_flash_sale_tables.sql 적용 완료
--
-- 결과:  flash_sales(title='LOADTEST_FLASH', status=ACTIVE, 현재 ±2시간 윈도)
--        flash_sale_items(remaining_quantity=:stock) 1건
-- ============================================================================

-- 1) 기존 부하 테스트용 세일이 있다면 정리(자식 테이블 포함, ON DELETE CASCADE)
DELETE FROM flash_sale_purchases
 WHERE flash_sale_id IN (SELECT flash_sale_id FROM flash_sales WHERE title = 'LOADTEST_FLASH');
DELETE FROM flash_sales WHERE title = 'LOADTEST_FLASH';

-- 2) 신규 세일 + 아이템 생성. stock=100, sale_price=9900, product_id=1.
WITH new_sale AS (
    INSERT INTO flash_sales (title, status, start_time, end_time)
    VALUES ('LOADTEST_FLASH', 'ACTIVE', NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '2 hours')
    RETURNING flash_sale_id
)
INSERT INTO flash_sale_items
    (flash_sale_id, product_id, sale_price, allocated_quantity, remaining_quantity, per_user_limit)
SELECT flash_sale_id, 1, 9900.00, 100, 100, 1
  FROM new_sale;

-- 3) 결과 확인
SELECT s.flash_sale_id, s.title, s.status,
       i.flash_sale_item_id, i.product_id, i.allocated_quantity, i.remaining_quantity
  FROM flash_sales s
  JOIN flash_sale_items i ON i.flash_sale_id = s.flash_sale_id
 WHERE s.title = 'LOADTEST_FLASH';
