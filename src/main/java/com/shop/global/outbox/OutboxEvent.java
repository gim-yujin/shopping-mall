package com.shop.global.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Transactional Outbox 이벤트 엔티티.
 *
 * <h3>Transactional Outbox 패턴이란?</h3>
 * <p>비즈니스 데이터(주문 생성/취소)와 이벤트 레코드를 같은 DB 트랜잭션에서 저장하여,
 * 트랜잭션 커밋 = 이벤트 저장을 원자적으로 보장하는 패턴이다.
 * 별도 폴러가 PENDING 이벤트를 읽어 처리하므로, 커밋 직후 애플리케이션이
 * 크래시해도 이벤트가 DB에 남아 있어 다음 폴링에서 재처리된다.</p>
 *
 * <h3>기존 방식의 문제</h3>
 * <pre>
 *   [기존] 비즈니스 트랜잭션 COMMIT → (gap) → Spring EventListener 실행
 *          ↑ 이 gap에서 크래시하면 이벤트 유실
 *
 *   [Outbox] 비즈니스 데이터 + Outbox INSERT → COMMIT (원자적)
 *            → 폴러가 PENDING 읽기 → 처리 → PROCESSED
 *            ↑ 폴러 실패해도 DB에 레코드 남아 있음 (at-least-once)
 * </pre>
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   PENDING ──▶ PROCESSED (폴러가 성공적으로 처리)
 *      │
 *      └──▶ retry_count 증가 + nextRetryAt 설정 (지수 백오프)
 *              │
 *              └──▶ DEAD_LETTER (MAX_RETRIES 초과, 수동 개입 필요)
 * </pre>
 *
 * <h3>[Phase 15] 지수 백오프 재시도 & Dead Letter</h3>
 * <p><b>문제:</b> 기존 재시도는 5초 간격으로 동일하게 반복되어, 일시적 장애(외부 서비스 다운)가
 * 해소되기 전에 재시도 횟수를 소진하고 FAILED로 전이되었다. 또한 FAILED 이벤트는
 * 수동 복구 경로가 없어 데이터가 영구적으로 유실될 수 있었다.</p>
 * <p><b>해결:</b> nextRetryAt 필드로 지수 백오프(10s→20s→40s→80s→160s)를 구현하여
 * 일시적 장애에 충분한 회복 시간을 부여한다. MAX_RETRIES 초과 시 DEAD_LETTER 상태로
 * 전이하여 관리자가 원인을 확인하고 수동 재시도할 수 있도록 한다.</p>
 *
 * <h3>이벤트 유형 (event_type)</h3>
 * <ul>
 *   <li>{@code PRODUCT_STOCK_CHANGED} — 재고 변경, 상품 상세 캐시 무효화</li>
 *   <li>{@code ORDER_CREATED} — 주문 생성, 외부 알림 발송</li>
 *   <li>{@code ORDER_CANCELLED} — 주문 취소, 외부 알림 발송</li>
 * </ul>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * [Phase 15] Dead Letter 상태: MAX_RETRIES 초과 후 수동 개입이 필요한 이벤트.
     *
     * <p><b>문제:</b> 기존 FAILED 상태는 이벤트를 영구적으로 버리는 것과 동일했다.
     * 외부 알림(ORDER_CREATED)이 FAILED로 전이되면 사용자에게 주문 확인 알림이
     * 발송되지 않는 데이터 유실이 발생했다.</p>
     * <p><b>해결:</b> DEAD_LETTER 상태로 분리하여 관리자가 장애 원인을 확인한 후
     * 수동으로 PENDING으로 되돌려 재처리할 수 있도록 한다.</p>
     */
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    /** 상품 재고 변경 (캐시 무효화 트리거) */
    public static final String TYPE_PRODUCT_STOCK_CHANGED = "PRODUCT_STOCK_CHANGED";

    /** [Phase 6] 주문 생성 완료 (외부 알림 발송 트리거) */
    public static final String TYPE_ORDER_CREATED = "ORDER_CREATED";

    /** [Phase 6] 주문 취소 완료 (외부 알림 발송 트리거) */
    public static final String TYPE_ORDER_CANCELLED = "ORDER_CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** 이벤트 데이터 JSON. 예: {"productIds":[1,2,3]} */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /**
     * [Phase 15] 다음 재시도 가능 시각.
     *
     * <p><b>문제:</b> 기존에는 PENDING 상태이기만 하면 매 폴링(5초)마다 즉시 재시도했다.
     * 외부 서비스 장애 시 5초 간격으로 5회 재시도하면 25초 안에 모든 기회를 소진한다.
     * 일시적 장애(네트워크 순단, 알림 서비스 재시작)는 보통 30초~수분이면 복구되므로,
     * 짧은 간격의 재시도는 불필요한 부하만 유발하고 복구 기회를 낭비한다.</p>
     * <p><b>해결:</b> 지수 백오프(10s, 20s, 40s, 80s, 160s)로 재시도 간격을 점진적으로
     * 늘려 장애 복구에 충분한 시간을 확보한다. 폴러는 nextRetryAt <= now()인
     * 이벤트만 조회하여 아직 대기 중인 이벤트를 건너뛴다.</p>
     */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * [Phase 15] 마지막 처리 실패 시 예외 메시지.
     *
     * <p><b>문제:</b> 기존에는 실패 원인이 로그에만 남아, 관리자가 FAILED 이벤트를
     * 확인할 때 해당 시점의 로그를 찾아야 했다. 로그 로테이션 이후에는
     * 원인 추적이 불가능했다.</p>
     * <p><b>해결:</b> 마지막 예외 메시지를 DB에 직접 저장하여, 이벤트 레코드만으로도
     * 실패 원인을 즉시 파악할 수 있도록 한다.</p>
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventType, String payload) {
        this.eventType = eventType;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
    }

    /** 폴러가 성공적으로 이벤트를 처리한 후 호출한다. */
    public void markProcessed() {
        this.status = STATUS_PROCESSED;
        this.processedAt = LocalDateTime.now();
    }

    /**
     * [Phase 15] 처리 실패 시 재시도 횟수를 증가시키고 지수 백오프를 적용한다.
     *
     * <p>백오프 공식: baseDelay × 2^(retryCount-1) 초.
     * 예: baseDelay=10이면 10s → 20s → 40s → 80s → 160s.
     * 캡은 300초(5분)로 제한하여 과도한 지연을 방지한다.</p>
     *
     * @param errorMessage 실패 원인 (예외 메시지)
     * @param baseDelaySec 기본 재시도 지연 (초)
     */
    public void scheduleRetry(String errorMessage, int baseDelaySec) {
        this.retryCount++;
        this.lastError = truncateError(errorMessage);
        // 지수 백오프: baseDelay × 2^(retryCount-1), 최대 300초
        long delaySec = Math.min((long) baseDelaySec * (1L << (this.retryCount - 1)), 300L);
        this.nextRetryAt = LocalDateTime.now().plusSeconds(delaySec);
    }

    /**
     * [Phase 15] 최대 재시도 횟수를 초과하면 Dead Letter로 전이한다.
     *
     * <p>Dead Letter 이벤트는 자동 재처리되지 않으며, 관리자가 원인을 확인한 후
     * {@code requeueFromDeadLetter()}로 PENDING 상태로 되돌려야 한다.</p>
     *
     * @param errorMessage 최종 실패 원인
     */
    @SuppressWarnings("PMD.NullAssignment") // Dead Letter 전이 시 nextRetryAt을 의도적으로 비운다
    public void moveToDeadLetter(String errorMessage) {
        this.retryCount++;
        this.status = STATUS_DEAD_LETTER;
        this.processedAt = LocalDateTime.now();
        this.lastError = truncateError(errorMessage);
        this.nextRetryAt = null;
    }

    /**
     * [Phase 15] Dead Letter 이벤트를 PENDING으로 되돌려 재처리를 허용한다.
     *
     * <p>관리자가 장애 원인을 해결한 후 호출한다.
     * retryCount를 0으로 초기화하여 전체 재시도 기회를 다시 부여한다.</p>
     */
    @SuppressWarnings("PMD.NullAssignment") // 상태 초기화: processedAt/nextRetryAt/lastError를 의도적으로 비운다
    public void requeueFromDeadLetter() {
        this.status = STATUS_PENDING;
        this.retryCount = 0;
        this.processedAt = null;
        this.nextRetryAt = null;
        this.lastError = null;
    }

    /** @deprecated Phase 15부터 {@link #scheduleRetry(String, int)} 사용. 하위 호환용. */
    @Deprecated
    public void incrementRetry() {
        this.retryCount++;
    }

    /** @deprecated Phase 15부터 {@link #moveToDeadLetter(String)} 사용. 하위 호환용. */
    @Deprecated
    public void markFailed() {
        this.status = STATUS_FAILED;
        this.processedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isDeadLetter() {
        return STATUS_DEAD_LETTER.equals(status);
    }

    /**
     * 에러 메시지를 500자로 제한하여 DB TEXT 컬럼에 과도한 데이터가 저장되는 것을 방지한다.
     */
    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    // ── Getters ──────────────────────────────────────────

    public Long getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public Integer getRetryCount() { return retryCount; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
}
