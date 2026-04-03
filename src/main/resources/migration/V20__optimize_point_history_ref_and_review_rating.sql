-- V20: Execution Plan 최적화 항목 7, 8
--
-- 항목 7: PointHistoryRepository.findByOrderId() 인덱스 선행 컬럼 최적화
-- 기존 idx_point_history_reference(reference_type, reference_id)는 reference_type이 선행 컬럼이라
-- reference_id 기반 조회 시 BitmapOr가 필요했다.
-- (reference_id, reference_type, created_at) 복합 인덱스로 단일 Index Range Scan + 정렬 제거.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_point_history_ref_order
    ON point_history(reference_id, reference_type, created_at);

-- 항목 8: ReviewService.updateProductRating() 쿼리 통합
-- 코드 변경만으로 해결 (2개 쿼리 → 1개 쿼리). 인덱스 추가 불필요.
-- 기존 idx_review_rating(product_id, rating)이 AVG(rating) + COUNT(*) Index-Only Scan을 지원.
