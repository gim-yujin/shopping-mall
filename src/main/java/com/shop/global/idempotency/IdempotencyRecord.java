package com.shop.global.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 멱등성 키 레코드 엔티티.
 *
 * <h3>도입 배경</h3>
 * <p>주문 생성 API에서 네트워크 타임아웃, 클라이언트 재시도, 더블 클릭 등으로
 * 동일한 요청이 중복 전송되면 같은 사용자에게 중복 주문이 생성될 수 있다.
 * 장바구니 기반 주문이므로 첫 번째 요청이 장바구니를 비우면 두 번째는
 * EMPTY_CART 에러가 발생하지만, 두 요청이 거의 동시에 도착하면
 * advisory lock 획득 전에 두 트랜잭션 모두 장바구니를 읽어
 * 중복 주문이 생성될 수 있다.</p>
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   (신규) ──▶ PROCESSING ──▶ COMPLETED  (성공: 응답 캐시됨)
 *                   │
 *                   └──▶ FAILED  (실패: 레코드 삭제 후 재시도 허용)
 * </pre>
 *
 * <h3>UNIQUE 제약 (user_id, idempotency_key)</h3>
 * <p>동시에 같은 키로 INSERT를 시도하면 하나만 성공하고 나머지는
 * DataIntegrityViolationException이 발생하여 중복이 물리적으로 차단된다.
 * 이것이 핵심 방어선이며, 애플리케이션 레벨 체크는 정상 흐름 최적화용이다.</p>
 *
 * <h3>보존 기간</h3>
 * <p>COMPLETED 레코드는 24시간 동안 유지되어 그 동안의 재시도에 캐시된 응답을 반환한다.
 * 24시간이 지난 레코드는 {@link IdempotencyCleanupScheduler}에 의해 배치 삭제된다.</p>
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    /** 처리 중 — 주문 생성 트랜잭션이 진행 중. 동일 키 재요청 시 409 Conflict. */
    public static final String STATUS_PROCESSING = "PROCESSING";

    /** 완료 — 주문 생성 성공. 동일 키 재요청 시 캐시된 응답 반환. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 실패 — 주문 생성 중 예외 발생. 레코드 삭제 후 재시도 허용. */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    /** 성공 시 직렬화된 응답 JSON (API용). SSR에서는 null. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String idempotencyKey, String resourceType) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.status = STATUS_PROCESSING;
        this.resourceType = resourceType;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 처리 완료로 상태를 전이한다.
     *
     * <p>주문 생성이 성공하면 생성된 리소스 ID와 직렬화된 응답을 기록한다.
     * 이후 동일 키로 재요청이 오면 DB 조회 없이 캐시된 responseBody를 반환한다.</p>
     *
     * @param resourceId   생성된 리소스 ID (예: orderId)
     * @param responseBody 직렬화된 JSON 응답 (API용)
     * @param httpStatus   HTTP 상태 코드 (예: 201)
     */
    public void markCompleted(Long resourceId, String responseBody, int httpStatus) {
        this.status = STATUS_COMPLETED;
        this.resourceId = resourceId;
        this.responseBody = responseBody;
        this.httpStatus = httpStatus;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * SSR용 완료 처리 — 응답 JSON 없이 리소스 ID만 기록한다.
     *
     * <p>SSR 주문에서는 JSON 응답 대신 리다이렉트 URL에 orderId를 포함하므로
     * responseBody를 저장할 필요가 없다. 중복 요청 시 orderId만으로
     * 동일한 리다이렉트를 생성할 수 있다.</p>
     */
    public void markCompletedForSsr(Long resourceId) {
        this.status = STATUS_COMPLETED;
        this.resourceId = resourceId;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = STATUS_FAILED;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    public boolean isProcessing() {
        return STATUS_PROCESSING.equals(status);
    }

    // ── Getters ──────────────────────────────────────────

    public Long getRecordId() { return recordId; }
    public Long getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public String getResourceType() { return resourceType; }
    public Long getResourceId() { return resourceId; }
    public String getResponseBody() { return responseBody; }
    public Integer getHttpStatus() { return httpStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
