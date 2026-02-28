package com.shop.domain.order.entity;

/**
 * 주문 아이템의 상태를 표현하는 enum.
 *
 * <h3>도입 배경</h3>
 * <p>기존에는 cancelledQuantity/returnedQuantity 수량 추적만으로 아이템 상태를 표현했다.
 * 이 방식은 "반품 신청 중"이라는 중간 상태를 표현할 수 없었고, 반품이 관리자 확인 없이
 * 즉시 처리되는 문제가 있었다.</p>
 *
 * <h3>상태 전이 다이어그램</h3>
 * <pre>
 *                          사용자 반품신청          관리자 승인        자동(Phase 1)
 *   NORMAL ──────────────▶ RETURN_REQUESTED ──▶ RETURN_APPROVED ──▶ RETURNED
 *     │                         │
 *     │ 부분 취소                │ 관리자 거절
 *     ▼                         ▼
 *   CANCELLED              RETURN_REJECTED
 *                               │
 *                               │ 사용자 재신청 (반품 기간 내)
 *                               ▼
 *                          RETURN_REQUESTED
 * </pre>
 *
 * <h3>설계 결정: RETURN_APPROVED → RETURNED 자동 전이</h3>
 * <p>실물 커머스에서는 승인 후 상품 회수·검수를 거쳐 최종 완료하지만,
 * 현재 프로젝트에는 물류 시스템이 없다. 따라서 Phase 1에서는 승인 시 즉시
 * RETURNED로 전이하고 환불을 실행한다. 향후 물류 연동 시 RETURN_APPROVED에서
 * 회수 완료 이벤트를 받아 RETURNED로 전이하도록 확장 가능하다.</p>
 */
public enum OrderItemStatus {

    NORMAL("정상", "bg-green-100 text-green-700"),
    RETURN_REQUESTED("반품신청", "bg-orange-100 text-orange-700"),
    RETURN_APPROVED("반품승인", "bg-blue-100 text-blue-700"),
    RETURNED("반품완료", "bg-gray-100 text-gray-700"),
    RETURN_REJECTED("반품거절", "bg-red-100 text-red-700"),
    CANCELLED("취소", "bg-red-100 text-red-700");

    private final String label;
    private final String badgeClass;

    OrderItemStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    /** UI에 표시할 한글 라벨 (예: "반품신청"). */
    public String getLabel() { return label; }

    /** Tailwind CSS 배지 클래스 (예: "bg-orange-100 text-orange-700"). */
    public String getBadgeClass() { return badgeClass; }

    /**
     * 현재 상태에서 대상 상태로의 전이가 허용되는지 검증한다.
     *
     * <p>잘못된 전이 시도를 컴파일 타임에 잡을 수 없으므로,
     * 런타임에 이 메서드로 방어한다. OrderItem의 상태 전이 메서드들은
     * 내부에서 이 메서드를 호출하여 불변 조건을 보장한다.</p>
     *
     * <h4>허용 전이 규칙:</h4>
     * <ul>
     *   <li>NORMAL → RETURN_REQUESTED (사용자 반품 신청)</li>
     *   <li>NORMAL → CANCELLED (사용자 부분 취소)</li>
     *   <li>RETURN_REQUESTED → RETURN_APPROVED (관리자 승인)</li>
     *   <li>RETURN_REQUESTED → RETURN_REJECTED (관리자 거절)</li>
     *   <li>RETURN_APPROVED → RETURNED (시스템 자동 — Phase 1에서는 승인과 동시)</li>
     *   <li>RETURN_REJECTED → RETURN_REQUESTED (사용자 재신청, 반품 기간 내)</li>
     *   <li>RETURNED, CANCELLED → 어떤 상태로도 전이 불가 (종결 상태)</li>
     * </ul>
     *
     * @param target 전이 대상 상태
     * @return 전이 허용 여부
     */
    public boolean canTransitionTo(OrderItemStatus target) {
        return switch (this) {
            case NORMAL -> target == RETURN_REQUESTED || target == CANCELLED;
            case RETURN_REQUESTED -> target == RETURN_APPROVED || target == RETURN_REJECTED;
            case RETURN_APPROVED -> target == RETURNED;
            case RETURN_REJECTED -> target == RETURN_REQUESTED;  // 재신청 허용
            case RETURNED, CANCELLED -> false;  // 종결 상태 — 추가 전이 불가
        };
    }
}
