package com.shop.global.idempotency;

import com.shop.global.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 멱등성 키 관리 서비스.
 *
 * <h3>멱등성 키 패턴이란?</h3>
 * <p>클라이언트가 요청마다 고유한 키(UUID)를 전달하면, 서버는 (userId, key) 조합으로
 * 중복 요청을 감지하여 동일한 응답을 반환하는 패턴이다.
 * Stripe, PayPal 등 결제 API에서 표준적으로 사용된다.</p>
 *
 * <h3>왜 별도 트랜잭션(REQUIRES_NEW)으로 분리하는가?</h3>
 * <p>멱등성 레코드의 INSERT/UPDATE는 주문 생성 트랜잭션과 독립적으로 커밋되어야 한다.
 * 주문 생성이 실패하여 롤백되더라도 FAILED 레코드는 남아있어야 하고,
 * 주문 생성이 성공하기 전에 PROCESSING 레코드가 먼저 커밋되어야
 * 동시 중복 요청을 차단할 수 있기 때문이다.</p>
 *
 * <h3>동시성 처리 흐름</h3>
 * <pre>
 *   요청 A (key=abc) ──▶ INSERT PROCESSING ──▶ 주문 생성 ──▶ UPDATE COMPLETED
 *   요청 B (key=abc) ──▶ INSERT 시도 ──▶ UNIQUE 위반 ──▶ SELECT ──▶ PROCESSING 확인 ──▶ 409 Conflict
 * </pre>
 *
 * <p>요청 A가 완료된 후 요청 B가 도착하면:</p>
 * <pre>
 *   요청 B (key=abc) ──▶ SELECT ──▶ COMPLETED 확인 ──▶ 캐시된 응답 반환
 * </pre>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 멱등성 키 최대 길이. UUID(36자) + 여유분. */
    static final int MAX_KEY_LENGTH = 64;

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 기존 멱등성 레코드를 조회한다.
     *
     * <p>호출부(컨트롤러/서비스)에서 이 결과를 기반으로 분기한다:</p>
     * <ul>
     *   <li>empty → {@link #initRecord}로 새 레코드 생성 후 실제 처리 진행</li>
     *   <li>COMPLETED → 캐시된 응답 반환 (재처리 없음)</li>
     *   <li>PROCESSING → 409 Conflict 반환</li>
     *   <li>FAILED → {@link #retryAfterFailure}로 FAILED 삭제 후 재처리</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findExisting(Long userId, String idempotencyKey) {
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    /**
     * PROCESSING 상태의 멱등성 레코드를 생성한다.
     *
     * <p>REQUIRES_NEW 전파로 독립 트랜잭션에서 즉시 커밋한다.
     * 이렇게 해야 주문 생성 트랜잭션이 진행되는 동안 다른 요청이
     * 이 PROCESSING 레코드를 볼 수 있어 중복을 차단할 수 있다.</p>
     *
     * <p>UNIQUE 제약 위반 시 DataIntegrityViolationException이 발생하며,
     * 이는 동시에 같은 키로 요청이 도착한 경우이다.
     * 호출부에서 이를 catch하여 409 Conflict를 반환한다.</p>
     *
     * @param userId        사용자 ID
     * @param idempotencyKey 클라이언트 제공 멱등성 키
     * @param resourceType  리소스 타입 (예: "ORDER")
     * @return 생성된 PROCESSING 상태 레코드
     * @throws DataIntegrityViolationException 동시 중복 요청 시 UNIQUE 위반
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord initRecord(Long userId, String idempotencyKey, String resourceType) {
        IdempotencyRecord record = new IdempotencyRecord(userId, idempotencyKey, resourceType);
        return repository.save(record);
    }

    /**
     * 주문 생성 성공 시 레코드를 COMPLETED로 전환한다 (API용).
     *
     * <p>REQUIRES_NEW로 독립 커밋하여, 주문 트랜잭션 커밋 직후
     * 캐시된 응답이 즉시 조회 가능하도록 보장한다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Long recordId, Long resourceId, String responseBody, int httpStatus) {
        IdempotencyRecord record = repository.findById(recordId)
                .orElseThrow(() -> new IllegalStateException("멱등성 레코드를 찾을 수 없습니다: " + recordId));
        record.markCompleted(resourceId, responseBody, httpStatus);
    }

    /**
     * 주문 생성 성공 시 레코드를 COMPLETED로 전환한다 (SSR용).
     *
     * <p>SSR에서는 JSON 응답 대신 리다이렉트 URL에 orderId를 사용하므로
     * responseBody를 저장하지 않는다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletedForSsr(Long recordId, Long resourceId) {
        IdempotencyRecord record = repository.findById(recordId)
                .orElseThrow(() -> new IllegalStateException("멱등성 레코드를 찾을 수 없습니다: " + recordId));
        record.markCompletedForSsr(resourceId);
    }

    /**
     * 주문 생성 실패 시 레코드를 FAILED로 전환한다.
     *
     * <p>REQUIRES_NEW로 독립 커밋하여, 주문 트랜잭션이 롤백되더라도
     * FAILED 레코드는 남아 있도록 보장한다. 클라이언트가 같은 키로
     * 재요청하면 FAILED 레코드를 삭제하고 재처리를 허용한다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long recordId) {
        repository.findById(recordId).ifPresent(record -> {
            record.markFailed();
            log.warn("멱등성 레코드 FAILED 처리 - recordId={}, key={}", recordId, record.getIdempotencyKey());
        });
    }

    /**
     * FAILED 레코드를 삭제하고 새로운 PROCESSING 레코드를 생성한다.
     *
     * <p>이전 요청이 실패한 경우, 클라이언트가 같은 키로 재시도할 수 있도록
     * FAILED 레코드를 제거한 뒤 새로운 PROCESSING 레코드를 생성한다.
     * 삭제와 생성을 하나의 트랜잭션으로 묶어 원자성을 보장한다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord retryAfterFailure(Long userId, String idempotencyKey, String resourceType) {
        int deleted = repository.deleteFailedRecord(userId, idempotencyKey);
        if (deleted == 0) {
            // 다른 스레드가 이미 FAILED를 삭제하고 재처리 중일 수 있음
            throw new BusinessException("IDEMPOTENCY_CONFLICT",
                    "요청이 이미 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }
        return repository.save(new IdempotencyRecord(userId, idempotencyKey, resourceType));
    }

    /**
     * 멱등성 키 형식을 검증한다.
     *
     * <p>허용 형식: 영문, 숫자, 하이픈으로 구성된 1~64자 문자열.
     * UUID v4 형식(8-4-4-4-12)을 권장하지만 강제하지는 않는다.</p>
     *
     * @throws BusinessException INVALID_IDEMPOTENCY_KEY — null, 빈 문자열, 길이 초과, 허용되지 않은 문자
     */
    public void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY",
                    "멱등성 키가 필요합니다. X-Idempotency-Key 헤더에 UUID를 전달해주세요.");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY",
                    "멱등성 키는 " + MAX_KEY_LENGTH + "자를 초과할 수 없습니다.");
        }
        // 영문, 숫자, 하이픈만 허용 — SQL injection 및 특수문자 방어
        if (!idempotencyKey.matches("^[a-zA-Z0-9\\-]+$")) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY",
                    "멱등성 키는 영문, 숫자, 하이픈만 사용할 수 있습니다.");
        }
    }
}
