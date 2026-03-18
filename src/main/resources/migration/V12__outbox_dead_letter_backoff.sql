-- =============================================================================
-- V12: Outbox Dead Letter & 지수 백오프 지원 컬럼 추가
-- =============================================================================
--
-- [Phase 15] 문제:
--   기존 재시도 전략은 5초 고정 간격으로 최대 5회 재시도한 뒤 FAILED로 전이했다.
--   이 방식은 두 가지 문제가 있었다:
--   1) 일시적 장애(외부 알림 서비스 재시작 등)가 25초 안에 복구되지 않으면
--      이벤트가 영구 유실되었다. 실제 장애 복구는 보통 30초~수분이 소요된다.
--   2) FAILED 상태의 이벤트는 자동 재처리도, 수동 재처리 경로도 없었다.
--      관리자가 DB에 직접 UPDATE 쿼리를 실행해야 했다.
--
-- 해결:
--   1) next_retry_at 컬럼으로 지수 백오프(10s→20s→40s→80s→160s)를 구현한다.
--      폴러는 next_retry_at <= now()인 이벤트만 조회하여 백오프를 존중한다.
--   2) DEAD_LETTER 상태를 추가하여 MAX_RETRIES 초과 이벤트를 명확히 분류한다.
--      관리자가 OutboxDeadLetterService를 통해 원인 확인 후 재시도할 수 있다.
--   3) last_error 컬럼에 마지막 예외 메시지를 저장하여, 로그 없이도
--      실패 원인을 즉시 파악할 수 있도록 한다.
-- =============================================================================

-- 지수 백오프 재시도 시각
ALTER TABLE outbox_events ADD COLUMN next_retry_at TIMESTAMP;

-- 마지막 실패 원인 메시지
ALTER TABLE outbox_events ADD COLUMN last_error TEXT;

-- CHECK 제약: DEAD_LETTER 상태 추가
ALTER TABLE outbox_events DROP CONSTRAINT chk_outbox_status;
ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_status
    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'DEAD_LETTER'));

-- Dead Letter 이벤트 조회용 부분 인덱스
CREATE INDEX idx_outbox_dead_letter ON outbox_events(processed_at)
    WHERE status = 'DEAD_LETTER';

-- 기존 FAILED → DEAD_LETTER 마이그레이션: 이미 FAILED인 레코드가 있다면 DEAD_LETTER로 전환
UPDATE outbox_events SET status = 'DEAD_LETTER' WHERE status = 'FAILED';

COMMENT ON COLUMN outbox_events.next_retry_at IS '[Phase 15] 지수 백오프 다음 재시도 시각. NULL이면 즉시 재시도 가능';
COMMENT ON COLUMN outbox_events.last_error IS '[Phase 15] 마지막 처리 실패 시 예외 메시지 (최대 500자)';
