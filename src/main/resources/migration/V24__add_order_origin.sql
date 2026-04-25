-- V24: orders.order_origin 추가 — 플래시 세일 주문 식별 마커
--
-- 배경:
--   docs/backlog-flash-sale.md §13-2 #6.
--   기존에는 플래시 세일 주문도 일반 주문과 같은 컬럼만 사용했기 때문에,
--   취소 시 OrderCancellationService가 products.stock_quantity를 인플레하고
--   flash_sale_items.remaining_quantity 는 복원하지 못해 정합성이 깨졌다.
--
-- 도메인 의존성 보존:
--   order ↔ flashsale 양방향 의존을 만들지 않기 위해 order 도메인은 본 마커만 가지고
--   분기하고, 보상은 FlashSaleOrderCancelledEvent 발행으로 flashsale 도메인 리스너에
--   위임한다(FlashSalePurchaseCancellationHandler).
--
-- Online migration:
--   IS NULL → 기본값 NOT NULL DEFAULT 'NORMAL' 1단계로 안전하다(orders.order_status와 동일 패턴).
--   ddl-auto=validate 운영 배포에서는 본 V24를 수동 적용 후 애플리케이션을 갱신해야 한다.

ALTER TABLE orders
    ADD COLUMN order_origin VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE orders
    ADD CONSTRAINT chk_order_origin CHECK (order_origin IN ('NORMAL', 'FLASH_SALE'));

COMMENT ON COLUMN orders.order_origin IS '주문 발행 경로: NORMAL(일반)|FLASH_SALE(플래시 세일). 취소 보상 분기에 사용';

-- 기존에 만들어진 플래시 세일 주문(flash_sale_purchases.order_id 로 식별)을 retroactive 표시.
-- V23 이후 본 V24 적용 사이에 생성된 주문이 있을 수 있다.
UPDATE orders
   SET order_origin = 'FLASH_SALE'
 WHERE order_id IN (SELECT order_id FROM flash_sale_purchases);
