-- =============================================================================
-- V9: orders 불변식 CHECK 제약 추가
-- =============================================================================
-- 목적:
--   주문 금액/포인트 정합성 불변식을 DB 레벨에서 강제한다.
--   - discount_amount = tier_discount_amount + coupon_discount_amount
--   - refunded_amount <= final_amount
--   - refunded_points <= used_points
--
-- 운영 안전성:
--   기존 운영 DB에 위반 데이터가 있을 수 있으므로 NOT VALID로 제약을 먼저 추가한다.
--   - 신규 INSERT/UPDATE는 즉시 제약 검증 대상이 된다.
--   - 기존 위반 행은 남아 있을 수 있으므로 사전/사후 점검 쿼리로 정리한다.
--   - 데이터 정리 후 VALIDATE CONSTRAINT를 수행해 완전 검증 상태로 전환한다.
-- =============================================================================

ALTER TABLE orders
    ADD CONSTRAINT chk_discount_breakdown CHECK (
        discount_amount = tier_discount_amount + coupon_discount_amount
    ) NOT VALID;

ALTER TABLE orders
    ADD CONSTRAINT chk_refunded_amount_limit
        CHECK (refunded_amount <= final_amount) NOT VALID;

ALTER TABLE orders
    ADD CONSTRAINT chk_refunded_points_limit
        CHECK (refunded_points <= used_points) NOT VALID;

-- 아래 VALIDATE는 기존 위반 데이터 정리 후 별도 배치/운영 점검 절차에서 수행한다.
-- ALTER TABLE orders VALIDATE CONSTRAINT chk_discount_breakdown;
-- ALTER TABLE orders VALIDATE CONSTRAINT chk_refunded_amount_limit;
-- ALTER TABLE orders VALIDATE CONSTRAINT chk_refunded_points_limit;
