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
 *      └──▶ retry_count 증가 (처리 실패 시) ──▶ FAILED (MAX_RETRIES 초과)
 * </pre>
 *
 * <h3>이벤트 유형 (event_type)</h3>
 * <ul>
 *   <li>{@code PRODUCT_STOCK_CHANGED} — 재고 변경, 상품 상세 캐시 무효화</li>
 *   <li>향후 확장: 알림 발송, 검색 인덱스 갱신, 외부 시스템 연동 등</li>
 * </ul>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    /** 현재 지원하는 이벤트 유형: 상품 재고 변경 (캐시 무효화 트리거) */
    public static final String TYPE_PRODUCT_STOCK_CHANGED = "PRODUCT_STOCK_CHANGED";

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

    /** 처리 실패 시 재시도 횟수를 증가시킨다. */
    public void incrementRetry() {
        this.retryCount++;
    }

    /** 최대 재시도 횟수를 초과하면 영구 실패로 전이한다. */
    public void markFailed() {
        this.status = STATUS_FAILED;
        this.processedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    // ── Getters ──────────────────────────────────────────

    public Long getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public Integer getRetryCount() { return retryCount; }
}
