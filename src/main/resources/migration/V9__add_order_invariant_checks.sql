-- =============================================================================
-- V9: orders 불변식 CHECK 제약 추가
-- =============================================================================
-- 목적:
--   주문 금액/포인트 정합성 불변식을 DB 레벨에서 강제한다.
--   - discount_amount = tier_discount_amount + coupon_discount_amount
--   - refunded_amount <= final_amount
--   - refunded_points <= used_points
--
-- 주의:
--   기존 데이터에 위반 행이 있으면 ADD CONSTRAINT 시점에 실패한다.
--   필요 시 사전 점검 쿼리(docs/order-invariant-checks.md)로 정리 후 적용한다.
-- =============================================================================

ALTER TABLE orders
    ADD CONSTRAINT chk_discount_breakdown CHECK (
        discount_amount = tier_discount_amount + coupon_discount_amount
    );

ALTER TABLE orders
    ADD CONSTRAINT chk_refunded_amount_limit
        CHECK (refunded_amount <= final_amount);

ALTER TABLE orders
    ADD CONSTRAINT chk_refunded_points_limit
        CHECK (refunded_points <= used_points);
