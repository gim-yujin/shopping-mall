-- [3.6] 배송 정보 확장: 택배사 및 송장번호 컬럼 추가
-- 관리자가 주문 상태를 SHIPPED로 변경할 때 택배사와 송장번호를 함께 기록한다.
-- 두 컬럼 모두 nullable이며, 배송 전 상태에서는 NULL이다.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_number VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS carrier VARCHAR(50);

COMMENT ON COLUMN orders.tracking_number IS '배송 추적번호 (SHIPPED 전환 시 입력)';
COMMENT ON COLUMN orders.carrier IS '택배사명 (SHIPPED 전환 시 입력)';
