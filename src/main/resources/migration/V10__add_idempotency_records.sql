-- =============================================================================
-- V10: 멱등성 키 레코드 테이블 추가
-- =============================================================================
--
-- 문제:
--   주문 생성 API에서 네트워크 타임아웃, 클라이언트 재시도, 더블 클릭 등으로
--   동일한 주문 요청이 중복 전송되면 같은 사용자에게 중복 주문이 생성된다.
--   장바구니 기반 주문이므로 첫 번째 요청이 장바구니를 비우면 두 번째는
--   EMPTY_CART 에러가 발생하지만, 두 요청이 거의 동시에 도착하면
--   advisory lock 획득 전에 두 트랜잭션 모두 장바구니를 읽어
--   중복 주문이 생성될 수 있다.
--
-- 해결:
--   클라이언트가 요청마다 고유한 멱등성 키(UUID)를 전달하면,
--   서버는 (user_id, idempotency_key) 조합으로 중복을 감지한다.
--   - 최초 요청: PROCESSING 레코드 삽입 → 주문 생성 → COMPLETED로 전환
--   - 중복 요청(COMPLETED): 저장된 응답을 그대로 반환 (재처리 없음)
--   - 중복 요청(PROCESSING): 409 Conflict 반환 (이전 요청 처리 중)
--   - 실패 후 재시도(FAILED): 레코드를 삭제하고 새로 처리 허용
--
-- UNIQUE 제약이 핵심 방어선:
--   동시에 같은 키로 INSERT를 시도하면 하나만 성공하고 나머지는
--   DataIntegrityViolationException이 발생하여 중복이 물리적으로 차단된다.
-- =============================================================================

CREATE TABLE idempotency_records (
    record_id       BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    resource_type   VARCHAR(50)  NOT NULL,
    resource_id     BIGINT,
    response_body   TEXT,
    http_status     INT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,

    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

-- 핵심 UNIQUE 제약: 같은 사용자가 같은 키로 중복 요청하는 것을 물리적으로 차단한다.
-- FAILED 상태의 레코드는 서비스 레벨에서 삭제 후 재시도를 허용하므로,
-- 이 제약은 PROCESSING/COMPLETED 상태에서만 의미를 갖지만
-- DB 레벨에서는 상태 무관하게 항상 유일성을 강제한다.
CREATE UNIQUE INDEX uk_idempotency_user_key
    ON idempotency_records (user_id, idempotency_key);

-- 만료 레코드 정리 스케줄러가 사용하는 인덱스.
-- created_at 기준으로 보존 기간이 지난 레코드를 배치 삭제할 때 활용한다.
CREATE INDEX idx_idempotency_created
    ON idempotency_records (created_at);

COMMENT ON TABLE idempotency_records IS '주문 생성 등 멱등성 보장이 필요한 API 요청의 중복 방지 레코드';
COMMENT ON COLUMN idempotency_records.idempotency_key IS '클라이언트가 생성한 UUID (X-Idempotency-Key 헤더 또는 폼 hidden field)';
COMMENT ON COLUMN idempotency_records.status IS 'PROCESSING=처리중, COMPLETED=완료(캐시된 응답 반환), FAILED=실패(재시도 허용)';
COMMENT ON COLUMN idempotency_records.resource_type IS '생성된 리소스 타입 (예: ORDER)';
COMMENT ON COLUMN idempotency_records.resource_id IS '생성된 리소스 ID (예: order_id). PROCESSING/FAILED 상태에서는 NULL';
COMMENT ON COLUMN idempotency_records.response_body IS '성공 시 직렬화된 응답 JSON (API용). SSR에서는 NULL';
COMMENT ON COLUMN idempotency_records.http_status IS '성공 시 HTTP 상태 코드 (예: 201)';
