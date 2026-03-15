-- =============================================================================
-- V11: Transactional Outbox 이벤트 테이블 추가
-- =============================================================================
--
-- 문제:
--   주문 생성/취소/부분취소 시 ProductStockChangedEvent를 Spring의
--   @TransactionalEventListener(AFTER_COMMIT)로 발행하고 있다.
--   이 방식은 트랜잭션 커밋 후 이벤트 처리가 인메모리에서만 진행되므로,
--   커밋 직후 애플리케이션이 크래시하면 이벤트가 유실된다.
--   캐시 무효화가 누락되면 사용자에게 품절 상품이 재고 있는 것처럼 보인다.
--
-- 해결 (Transactional Outbox 패턴):
--   비즈니스 데이터(주문)와 이벤트 레코드(outbox)를 같은 트랜잭션에서 저장한다.
--   별도 폴러(OutboxEventPoller)가 주기적으로 PENDING 이벤트를 읽어 처리하고
--   PROCESSED로 상태를 전이한다. 폴러가 실패해도 레코드가 DB에 남아있으므로
--   다음 폴링 주기에 재처리된다 (at-least-once 보장).
--
-- 향후 확장:
--   현재는 캐시 무효화만 처리하지만, 이 테이블을 통해 알림 발송, 외부 시스템 연동,
--   검색 인덱스 갱신 등 다양한 비동기 후속 처리를 안전하게 추가할 수 있다.
-- =============================================================================

CREATE TABLE outbox_events (
    event_id        BIGSERIAL    PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    retry_count     INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
);

-- 폴러가 PENDING 상태의 이벤트를 생성 순서대로 조회하는 핵심 인덱스.
-- status 필터 + created_at 정렬을 하나의 인덱스로 처리한다.
CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at)
    WHERE status = 'PENDING';

-- 정리 스케줄러가 처리 완료된 오래된 이벤트를 배치 삭제할 때 사용.
CREATE INDEX idx_outbox_processed_at ON outbox_events (processed_at)
    WHERE status = 'PROCESSED';

COMMENT ON TABLE outbox_events IS 'Transactional Outbox: 비즈니스 트랜잭션과 함께 저장되는 이벤트 레코드';
COMMENT ON COLUMN outbox_events.event_type IS '이벤트 유형 (예: PRODUCT_STOCK_CHANGED)';
COMMENT ON COLUMN outbox_events.payload IS '이벤트 데이터 JSON (예: {"productIds":[1,2,3]})';
COMMENT ON COLUMN outbox_events.status IS 'PENDING=미처리, PROCESSED=처리완료, FAILED=영구실패';
COMMENT ON COLUMN outbox_events.retry_count IS '처리 재시도 횟수. MAX_RETRIES 초과 시 FAILED로 전이';
