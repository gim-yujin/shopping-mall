-- =============================================================================
-- V8: OrderItem 상태 enum 도입 + 관리자 반품 승인 워크플로우 기반 컬럼
-- =============================================================================
--
-- 문제:
--   기존 OrderItem은 cancelledQuantity/returnedQuantity 수량 추적만으로 상태를 표현했다.
--   이 방식에는 세 가지 한계가 있었다.
--     1) 반품이 즉시 처리됨 — 관리자 확인 없이 재고 복구·환불이 실행됨
--     2) 아이템의 현재 상태를 단일 필드로 질의할 수 없음
--     3) 관리자 대시보드에서 반품 건을 관리할 수 없음
--
-- 해결:
--   OrderItemStatus enum(NORMAL, RETURN_REQUESTED, RETURN_APPROVED, RETURNED,
--   RETURN_REJECTED, CANCELLED)을 도입하여 상태 머신 기반의 반품 워크플로우를 구현한다.
--   사용자 반품 신청(RETURN_REQUESTED) → 관리자 승인(RETURNED) / 거절(RETURN_REJECTED)
--   단계를 거치도록 하여 실제 커머스의 반품 검수 프로세스를 반영한다.
--
-- 하위 호환성:
--   - 모든 컬럼은 DEFAULT 값과 함께 추가되므로 기존 데이터에 영향 없음
--   - 기존 API 응답 필드 제거 없이 신규 필드만 추가
--   - 이미 반품/취소 완료된 기존 데이터는 UPDATE 문으로 상태를 보정
-- =============================================================================

-- 1) status 컬럼 추가 (기본값 NORMAL)
--    모든 기존 아이템은 NORMAL로 시작한다.
ALTER TABLE order_items
    ADD COLUMN status VARCHAR(20) DEFAULT 'NORMAL' NOT NULL;

-- 2) 반품 사유 컬럼 (사용자가 반품 신청 시 선택)
--    DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER 등
ALTER TABLE order_items
    ADD COLUMN return_reason VARCHAR(500);

-- 3) 관리자 거절 사유 컬럼 (관리자가 반품 거절 시 입력)
ALTER TABLE order_items
    ADD COLUMN reject_reason VARCHAR(500);

-- 4) 반품 대기 수량 (방법 A 채택: 부분 반품 시나리오 지원)
--    반품 신청 시 pendingReturnQuantity에 수량을 기록하고,
--    getRemainingQuantity() 계산에 포함하여 중복 반품 신청을 방지한다.
--    승인 시 이 수량을 returnedQuantity로 이동, 거절 시 원복한다.
ALTER TABLE order_items
    ADD COLUMN pending_return_quantity INT DEFAULT 0 NOT NULL;

-- 5) 반품 신청 일시 (사용자 신청 시점 기록)
ALTER TABLE order_items
    ADD COLUMN return_requested_at TIMESTAMP;

-- 6) 반품 완료 일시 (관리자 승인 시점 기록)
ALTER TABLE order_items
    ADD COLUMN returned_at TIMESTAMP;

-- 7) CHECK 제약 — 유효한 상태 값만 허용
ALTER TABLE order_items
    ADD CONSTRAINT chk_order_item_status CHECK (
        status IN ('NORMAL', 'RETURN_REQUESTED', 'RETURN_APPROVED',
                   'RETURNED', 'RETURN_REJECTED', 'CANCELLED')
    );

-- 8) pending_return_quantity 음수 방지
ALTER TABLE order_items
    ADD CONSTRAINT chk_pending_return_quantity CHECK (pending_return_quantity >= 0);

-- 9) 기존 데이터 보정
--    이미 전량 반품된 아이템 → RETURNED
UPDATE order_items
SET status = 'RETURNED'
WHERE returned_quantity > 0 AND returned_quantity >= quantity;

--    이미 전량 취소된 아이템 → CANCELLED
UPDATE order_items
SET status = 'CANCELLED'
WHERE cancelled_quantity > 0 AND cancelled_quantity >= quantity;

-- 10) 관리자 반품 대기 목록 조회용 partial index
--     RETURN_REQUESTED 상태의 아이템만 인덱싱하여 관리자 페이지 성능을 보장한다.
--     전체 order_items 대비 반품 신청 건은 극소수이므로 partial index가 효율적이다.
CREATE INDEX idx_order_items_status_return_requested
    ON order_items (status)
    WHERE status = 'RETURN_REQUESTED';

-- 11) 코멘트
COMMENT ON COLUMN order_items.status IS '아이템 상태: NORMAL, RETURN_REQUESTED, RETURN_APPROVED, RETURNED, RETURN_REJECTED, CANCELLED';
COMMENT ON COLUMN order_items.return_reason IS '사용자 반품 사유 (DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)';
COMMENT ON COLUMN order_items.reject_reason IS '관리자 반품 거절 사유';
COMMENT ON COLUMN order_items.pending_return_quantity IS '반품 대기 수량 — 신청 시 증가, 승인/거절 시 차감';
COMMENT ON COLUMN order_items.return_requested_at IS '반품 신청 일시';
COMMENT ON COLUMN order_items.returned_at IS '반품 완료(승인) 일시';
