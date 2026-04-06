package com.shop.global.idempotency;

import com.shop.global.dto.ApiResponse;
import com.shop.global.metrics.IdempotencyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 멱등성 키 패턴의 공통 실행 흐름을 캡슐화한다.
 *
 * <p>주문 생성/취소/부분취소, 쿠폰 발급 등 멱등성이 필요한 엔드포인트에서
 * 반복되던 ~50줄의 보일러플레이트(키 검증 → 기존 레코드 조회 → 상태 분기 →
 * 레코드 생성 → 실행 → 실패 시 FAILED 전환)를 단일 메서드로 통합한다.</p>
 *
 * <h3>사용법</h3>
 * <pre>
 * // 값을 반환하는 연산 (주문 생성)
 * idempotencyExecutor.execute(
 *     userId, idempotencyKey, "ORDER", 201,
 *     this::deserializeCachedResponse,       // COMPLETED 시 캐시 응답 반환
 *     () -&gt; orderService.createOrder(...),   // 비즈니스 로직
 *     Order::getOrderId,                     // 리소스 ID 추출
 *     OrderDetailResponse::from);            // 응답 DTO 변환
 *
 * // Void 연산 (취소, 쿠폰 발급)
 * idempotencyExecutor.executeVoid(
 *     userId, idempotencyKey, "ORDER_CANCEL", 200, orderId,
 *     () -&gt; orderService.cancelOrder(orderId, userId));
 * </pre>
 */
@Component
public class IdempotencyExecutor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyExecutor.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyMetrics idempotencyMetrics;

    public IdempotencyExecutor(IdempotencyService idempotencyService,
                               IdempotencyMetrics idempotencyMetrics) {
        this.idempotencyService = idempotencyService;
        this.idempotencyMetrics = idempotencyMetrics;
    }

    /**
     * 값을 반환하는 멱등성 실행.
     *
     * @param userId            사용자 ID
     * @param idempotencyKey    클라이언트 제공 멱등성 키
     * @param resourceType      리소스 타입 (예: "ORDER")
     * @param successStatus     성공 시 HTTP 상태 코드
     * @param onCompleted       COMPLETED 레코드 발견 시 캐시 응답 생성 콜백
     * @param action            비즈니스 로직 (결과 반환)
     * @param resourceIdExtractor 결과에서 리소스 ID 추출
     * @param responseMapper    결과를 응답 DTO로 변환
     * @param <E> 비즈니스 로직 반환 타입 (예: Order)
     * @param <R> 응답 DTO 타입 (예: OrderDetailResponse)
     */
    public <E, R> ResponseEntity<ApiResponse<R>> execute(
            Long userId,
            String idempotencyKey,
            String resourceType,
            int successStatus,
            Function<IdempotencyRecord, ResponseEntity<ApiResponse<R>>> onCompleted,
            Supplier<E> action,
            Function<E, Long> resourceIdExtractor,
            Function<E, R> responseMapper) {

        return doExecute(userId, idempotencyKey, resourceType,
                onCompleted,
                record -> {
                    E result = idempotencyService.executeWithCompletion(
                            record.getRecordId(), action, resourceIdExtractor, successStatus);
                    return ResponseEntity.status(successStatus)
                            .body(ApiResponse.ok(responseMapper.apply(result)));
                });
    }

    /**
     * Void 멱등성 실행 (취소, 부분취소, 쿠폰 발급 등).
     *
     * <p>COMPLETED 시 {@code ApiResponse.ok()}를 반환한다.
     * 비즈니스 로직에 반환값이 없으므로 {@code onCompleted} 콜백이 불필요하다.</p>
     *
     * @param userId         사용자 ID
     * @param idempotencyKey 클라이언트 제공 멱등성 키
     * @param resourceType   리소스 타입 (예: "ORDER_CANCEL")
     * @param successStatus  성공 시 HTTP 상태 코드
     * @param resourceId     대상 리소스 ID
     * @param action         비즈니스 로직 (반환값 없음)
     */
    public ResponseEntity<ApiResponse<Void>> executeVoid(
            Long userId,
            String idempotencyKey,
            String resourceType,
            int successStatus,
            Long resourceId,
            Runnable action) {

        return doExecute(userId, idempotencyKey, resourceType,
                prev -> ResponseEntity.status(successStatus).body(ApiResponse.ok()),
                record -> {
                    idempotencyService.executeAndMarkCompleted(
                            record.getRecordId(), resourceId, successStatus, action);
                    return ResponseEntity.status(successStatus).body(ApiResponse.ok());
                });
    }

    /**
     * 멱등성 키 패턴의 공통 실행 흐름.
     *
     * <pre>
     * 1. 키 형식 검증
     * 2. 기존 레코드 조회 → COMPLETED/PROCESSING/FAILED 분기
     * 3. PROCESSING 레코드 생성 (UNIQUE 충돌 시 409)
     * 4. 비즈니스 로직 실행 (실패 시 FAILED 전환)
     * </pre>
     */
    private <T> ResponseEntity<ApiResponse<T>> doExecute(
            Long userId,
            String idempotencyKey,
            String resourceType,
            Function<IdempotencyRecord, ResponseEntity<ApiResponse<T>>> onCompleted,
            Function<IdempotencyRecord, ResponseEntity<ApiResponse<T>>> onExecute) {

        idempotencyService.validateKey(idempotencyKey);

        // 1단계: 기존 레코드 확인
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord prev = existing.get();

            if (prev.isCompleted()) {
                idempotencyMetrics.recordDuplicateCompleted();
                log.info("멱등성 키 중복 (COMPLETED) - userId={}, key={}, resourceType={}, resourceId={}",
                        userId, idempotencyKey, resourceType, prev.getResourceId());
                return onCompleted.apply(prev);
            }

            if (prev.isProcessing()) {
                idempotencyMetrics.recordDuplicateProcessing();
                log.warn("멱등성 키 중복 (PROCESSING) - userId={}, key={}, resourceType={}",
                        userId, idempotencyKey, resourceType);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error("IDEMPOTENCY_PROCESSING",
                                "이전 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
            }

            // FAILED — 재시도 허용
            idempotencyMetrics.recordRetry();
            log.info("멱등성 키 재시도 (FAILED) - userId={}, key={}, resourceType={}",
                    userId, idempotencyKey, resourceType);
        }

        // 2단계: PROCESSING 레코드 생성
        IdempotencyRecord record;
        boolean isRetry = existing.isPresent();
        try {
            record = isRetry
                    ? idempotencyService.retryAfterFailure(userId, idempotencyKey, resourceType)
                    : idempotencyService.initRecord(userId, idempotencyKey, resourceType);
        } catch (DataIntegrityViolationException e) {
            idempotencyMetrics.recordConflict();
            log.info("멱등성 키 동시 삽입 충돌 - userId={}, key={}, resourceType={}",
                    userId, idempotencyKey, resourceType);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("IDEMPOTENCY_CONFLICT",
                            "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
        }

        idempotencyMetrics.recordNew();

        // 3단계: 비즈니스 로직 실행
        try {
            return onExecute.apply(record);
        } catch (Exception e) {
            idempotencyService.markFailed(record.getRecordId());
            throw e;
        }
    }
}
