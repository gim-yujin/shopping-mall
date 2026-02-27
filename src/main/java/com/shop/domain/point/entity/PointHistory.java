package com.shop.domain.point.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 포인트 변동 이력 엔티티.
 *
 * [신규] 기존에는 포인트 적립/사용/환불이 User.pointBalance에만 반영되어
 * 개별 변동 이력을 추적할 수 없었다. 재고는 ProductInventoryHistory로 모든 변동을
 * 추적하면서, 같은 금전적 가치를 가진 포인트는 이력이 없는 비대칭이 존재했다.
 *
 * 이 테이블은 모든 포인트 변동(적립, 사용, 환불, 만료, 수동 조정)을 기록하여
 * 고객 문의 대응("내 포인트가 왜 줄었나요?")과 감사(audit) 추적을 가능하게 한다.
 *
 * amount는 항상 양수이며, changeType으로 증감 방향을 구분한다:
 *   - EARN:   포인트 적립 (잔액 증가) — 배송 완료 시 정산
 *   - USE:    포인트 사용 (잔액 감소) — 주문 결제 시 차감
 *   - REFUND: 포인트 환불 (잔액 증가) — 주문 취소 시 사용 포인트 복원
 *   - EXPIRE: 포인트 만료 (잔액 감소) — 만료 정책 적용 시 (추후 구현)
 *   - ADJUST: 수동 조정 (관리자)     — CS 보상 등 (추후 구현)
 */
@Entity
@Table(name = "point_history")
public class PointHistory {

    // ── 변동 유형 상수 ────────────────────────────────────
    public static final String EARN   = "EARN";
    public static final String USE    = "USE";
    public static final String REFUND = "REFUND";
    public static final String EXPIRE = "EXPIRE";
    public static final String ADJUST = "ADJUST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 변동 유형. EARN / USE / REFUND / EXPIRE / ADJUST 중 하나.
     * DB CHECK 제약으로 유효값을 강제한다.
     */
    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    /**
     * 변동 수량 (항상 양수).
     * 증감 방향은 changeType으로 구분한다.
     * 예: EARN 1500 → 1500P 적립, USE 500 → 500P 사용.
     */
    @Column(name = "amount", nullable = false)
    private Integer amount;

    /**
     * 변동 후 잔액 스냅샷.
     * 변동이 적용된 직후의 User.pointBalance 값을 기록한다.
     * 이력을 시간순으로 나열하면 잔액 변동 추이를 확인할 수 있다.
     */
    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    /**
     * 참조 유형. 이 변동을 유발한 비즈니스 이벤트의 종류.
     * ORDER: 주문(적립/사용), CANCEL: 주문 취소(환불), ADMIN: 관리자 조정, SYSTEM: 시스템(만료 등).
     */
    @Column(name = "reference_type", length = 20)
    private String referenceType;

    /**
     * 참조 ID. referenceType에 따라 다른 엔티티의 PK를 가리킨다.
     * ORDER/CANCEL → orders.order_id, ADMIN → 관리자 user_id 등.
     */
    @Column(name = "reference_id")
    private Long referenceId;

    /**
     * 사람이 읽을 수 있는 변동 설명.
     * 예: "주문 적립 (주문번호: 20240101...-ABC123)", "주문 취소 환불".
     */
    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PointHistory() {}

    public PointHistory(Long userId, String changeType, int amount, int balanceAfter,
                        String referenceType, Long referenceId, String description) {
        this.userId = userId;
        this.changeType = changeType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    // Getters
    public Long getHistoryId() { return historyId; }
    public Long getUserId() { return userId; }
    public String getChangeType() { return changeType; }
    public Integer getAmount() { return amount; }
    public Integer getBalanceAfter() { return balanceAfter; }
    public String getReferenceType() { return referenceType; }
    public Long getReferenceId() { return referenceId; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
