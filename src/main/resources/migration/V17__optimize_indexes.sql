-- ============================================================================
-- V17: 인덱스 최적화 — 중복 제거 + 누락 인덱스 추가
-- ============================================================================

-- ────────────────────────────────────────────────────────────────────────────
-- A. 중복 인덱스 삭제
-- PostgreSQL은 UNIQUE 컬럼 제약조건에 자동으로 unique index를 생성한다.
-- 별도로 만든 non-unique index는 중복이므로 쓰기 오버헤드와 디스크만 낭비한다.
-- ────────────────────────────────────────────────────────────────────────────

-- orders.order_number: UNIQUE NOT NULL 제약이 이미 unique index 생성
DROP INDEX IF EXISTS idx_order_number;

-- coupons.coupon_code: UNIQUE NOT NULL 제약이 이미 unique index 생성
DROP INDEX IF EXISTS idx_coupon_code;

-- users.username: UNIQUE NOT NULL 제약이 이미 unique index 생성
DROP INDEX IF EXISTS idx_users_username;

-- users.email: UNIQUE NOT NULL 제약이 이미 unique index 생성
DROP INDEX IF EXISTS idx_users_email;

-- review_helpfuls(review_id): uk_helpful_review_user(review_id, user_id)의
-- 선두 컬럼으로 이미 커버됨 (B-tree 선두 컬럼 원리)
DROP INDEX IF EXISTS idx_review_helpful_review;

-- ────────────────────────────────────────────────────────────────────────────
-- B. 누락 인덱스 추가
-- ────────────────────────────────────────────────────────────────────────────

-- 1) v_product_list 뷰의 썸네일 서브쿼리 최적화.
--    기존 idx_image_product(product_id, image_order)는 is_thumbnail을 커버하지 않아
--    매 상품마다 heap 접근 후 필터링이 필요했다.
--    Partial index로 is_thumbnail=true 행만 인덱싱하여 즉시 반환.
CREATE INDEX idx_image_thumbnail ON product_images(product_id) WHERE is_thumbnail = true;

-- 2) review_helpfuls 사용자별 조회 최적화.
--    findHelpedReviewIdsByUserIdAndReviewIds(userId, reviewIds)가
--    기존 idx_review_helpful_user(user_id)로는 review_id IN 필터링에 heap 접근 필요.
--    (user_id, review_id) 복합 인덱스로 index-only scan 가능.
DROP INDEX IF EXISTS idx_review_helpful_user;
CREATE INDEX idx_review_helpful_user ON review_helpfuls(user_id, review_id);

-- 3) outbox_events 재시도 폴링 최적화.
--    findRetryEventsForUpdate()가 next_retry_at <= NOW() 조건으로 필터하지만
--    기존 idx_outbox_pending(status, created_at) WHERE PENDING은 next_retry_at 미포함.
--    재시도 대상만 별도 partial index로 인덱싱.
CREATE INDEX idx_outbox_retry ON outbox_events(next_retry_at)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;
