-- V25: flash_sale_purchases.flash_sale_item_id 추가
--
-- 배경:
--   docs/backlog-flash-sale.md §13-2 #6.
--   주문 취소 시 잔여 수량(`flash_sale_items.remaining_quantity`)을 복원하려면
--   어느 아이템을 차감했는지 알아야 한다. 기존 스키마는 `flash_sale_id`만 보관해서
--   1세일에 다수 아이템이 달리면 어떤 행을 복원할지 결정 불가능했다.
--
--   더불어 본 컬럼은 감사 로그로서도 가치가 있다 — 어떤 사용자가 어떤 세일·어떤 상품을
--   샀는지가 한 행에 모인다.

ALTER TABLE flash_sale_purchases
    ADD COLUMN flash_sale_item_id BIGINT;

-- 기존 행에 대해 retroactive 채움.
-- MVP는 1세일 = 1아이템이라 같은 flash_sale_id 안에 단일 행이 보장됨.
-- 다중 아이템이 이미 존재한다면 MIN()로 결정적 선택(운영상 V25 적용 시점에 다중 아이템
-- 세일이 운영 중인 경우는 없는 것으로 가정).
UPDATE flash_sale_purchases p
   SET flash_sale_item_id = (
       SELECT MIN(i.flash_sale_item_id)
         FROM flash_sale_items i
        WHERE i.flash_sale_id = p.flash_sale_id
   );

ALTER TABLE flash_sale_purchases ALTER COLUMN flash_sale_item_id SET NOT NULL;
ALTER TABLE flash_sale_purchases
    ADD CONSTRAINT fk_fsp_item FOREIGN KEY (flash_sale_item_id)
        REFERENCES flash_sale_items(flash_sale_item_id);

COMMENT ON COLUMN flash_sale_purchases.flash_sale_item_id IS '구매된 세일 아이템. 취소 보상 시 remaining_quantity 복원 대상';
