-- ============================================================================
-- V27: user_coupons.order_id에 UNIQUE 제약 부여 (1주문 1쿠폰 정책의 DB 강제)
-- ============================================================================
--
-- 배경
--   도메인 정책상 "1개 주문에는 최대 1개 쿠폰" 규칙이 적용되어 왔다.
--   그러나 user_coupons 테이블에는 order_id에 대한 UNIQUE 제약이 없어,
--   데이터 마이그레이션·관리자 직접 수정·향후 정책 변경으로 동일 order_id에 2행이
--   생기면 Spring Data의 findByOrderId(Long) → Optional<UserCoupon> 시그니처가
--   IncorrectResultSizeDataAccessException으로 폭발한다.
--   이 예외는 주문 취소(OrderCancellationService.cancelOrderInternal:193)와
--   부분취소 누적 취소(PartialCancellationService.transitionIfFullyCancelled:462)
--   양쪽 경로에서 발생하여 취소 자체를 차단한다.
--
-- 조치
--   1) 사전 검증: 운영 데이터에 동일 order_id가 둘 이상인 행이 있으면 마이그레이션을
--      실패시킨다. 정합성 회복 절차는 별도 점검 스크립트로 수행해야 한다.
--   2) 기존 비-UNIQUE 부분 인덱스 idx_user_coupon_order를 동일 컬럼·술어의
--      UNIQUE 부분 인덱스 uk_user_coupon_order로 대체한다. 부분 인덱스가
--      필터를 그대로 포함하므로 기존 조회 성능을 유지한다.

-- ── 1단계: 사전 검증 ─────────────────────────────────────────────────────────
DO $$
DECLARE
    duplicate_rows INT;
BEGIN
    SELECT COUNT(*) INTO duplicate_rows FROM (
        SELECT order_id
          FROM user_coupons
         WHERE order_id IS NOT NULL
         GROUP BY order_id
        HAVING COUNT(*) > 1
    ) AS dup;

    IF duplicate_rows > 0 THEN
        RAISE EXCEPTION
            'user_coupons.order_id에 중복된 값이 % 건 존재합니다. UNIQUE 제약 부여 전 데이터 정합성 점검이 필요합니다.',
            duplicate_rows;
    END IF;
END $$;

-- ── 2단계: 기존 비-UNIQUE 부분 인덱스 제거 ───────────────────────────────────
DROP INDEX IF EXISTS idx_user_coupon_order;

-- ── 3단계: UNIQUE 부분 인덱스 생성 ───────────────────────────────────────────
-- order_id IS NOT NULL 조건은 미사용 쿠폰(약 70~80%)을 제외하여 인덱스 크기를 줄이고,
-- UNIQUE 제약은 사용 중인 쿠폰에 대해서만 적용된다. NULL 끼리는 충돌하지 않으므로
-- 미사용 쿠폰은 영향을 받지 않는다.
CREATE UNIQUE INDEX uk_user_coupon_order
    ON user_coupons(order_id)
 WHERE order_id IS NOT NULL;
