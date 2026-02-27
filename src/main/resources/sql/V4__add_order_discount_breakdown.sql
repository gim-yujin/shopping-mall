-- ============================================================================
-- [P2-11] 주문 할인 내역 분리 마이그레이션
--
-- 기존 discount_amount는 등급 할인 + 쿠폰 할인의 합산값이어서
-- 감사/정산 시 개별 할인 출처를 추적할 수 없었다.
--
-- 변경: tier_discount_amount, coupon_discount_amount 컬럼을 추가하여
-- 할인 출처별 금액을 별도 기록한다.
-- discount_amount = tier_discount_amount + coupon_discount_amount 관계가 유지된다.
--
-- 기존 데이터: 이미 생성된 주문의 할인 출처를 소급 분리할 수 없으므로
-- 신규 컬럼의 기본값을 0으로 설정한다.
-- 이 마이그레이션 이후 생성되는 주문부터 정확한 분리 값이 기록된다.
-- ============================================================================

ALTER TABLE orders
    ADD COLUMN tier_discount_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL;

ALTER TABLE orders
    ADD COLUMN coupon_discount_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL;

COMMENT ON COLUMN orders.tier_discount_amount IS '회원 등급(BRONZE~DIAMOND)에 의한 할인 금액';
COMMENT ON COLUMN orders.coupon_discount_amount IS '쿠폰 적용에 의한 할인 금액';
