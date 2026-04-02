-- ============================================================================
-- V19: Execution Plan 최적화 후속 조치
--  1) TierScheduler 연간 실적 집계용 partial covering index 추가
-- ============================================================================

-- findYearlySpentByUser():
--   WHERE order_status <> 'CANCELLED'
--     AND order_date >= :startDate AND order_date < :endDate
--   GROUP BY user_id
--
-- 기존 idx_order_date(order_date DESC)는 날짜 범위 필터에는 활용되지만,
-- user_id/final_amount를 읽기 위해 heap 접근이 필요했다.
-- 취소 주문을 제외한 partial covering index로 연간 집계의 Index-Only Scan 가능성을 높인다.
CREATE INDEX IF NOT EXISTS idx_order_yearly_spent_non_cancelled
    ON orders(order_date)
    INCLUDE (user_id, final_amount)
    WHERE order_status <> 'CANCELLED';
