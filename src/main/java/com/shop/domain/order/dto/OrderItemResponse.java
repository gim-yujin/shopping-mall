package com.shop.domain.order.dto;

import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 항목 응답 DTO.
 *
 * <h3>[Step 3] 상태 필드 추가</h3>
 *
 * <p><b>문제:</b> 기존 OrderItemResponse에는 수량/금액 필드만 있어서
 * REST API 클라이언트가 아이템의 반품 진행 상태를 알 수 없었다.
 * 반품 신청 후 "대기 중", "거절됨" 같은 상태 표시가 불가능했고,
 * 관리자 거절 사유도 전달할 수 없었다.</p>
 *
 * <p><b>해결:</b> OrderItemStatus 관련 필드를 추가한다.
 * <ul>
 *   <li>{@code status} — enum name (API 소비자가 분기 로직에 사용)</li>
 *   <li>{@code statusLabel} — 한글 레이블 ("반품신청", "반품완료" 등 UI 표시용)</li>
 *   <li>{@code statusBadgeClass} — Tailwind CSS 배지 클래스 (SSR 템플릿용)</li>
 *   <li>{@code returnReason} — 사용자 반품 사유</li>
 *   <li>{@code rejectReason} — 관리자 거절 사유</li>
 *   <li>{@code pendingReturnQuantity} — 반품 대기 수량</li>
 *   <li>{@code returnRequestedAt}, {@code returnedAt} — 타임스탬프</li>
 * </ul>
 * </p>
 *
 * <p><b>하위 호환성:</b> 기존 필드를 제거하지 않고 신규 필드만 추가했다.
 * 기존 API 소비자는 새 필드를 무시하면 되므로 하위 호환이 유지된다.</p>
 */
public record OrderItemResponse(
        // ── 기존 필드 (하위 호환 유지) ──
        Long orderItemId,
        Long productId,
        String productName,
        int quantity,
        int cancelledQuantity,
        int returnedQuantity,
        int remainingQuantity,
        BigDecimal unitPrice,
        BigDecimal subtotal,
        BigDecimal cancelledAmount,
        BigDecimal returnedAmount,

        // ── Step 3 신규: 상태 필드 ──
        String status,
        String statusLabel,
        String statusBadgeClass,
        String returnReason,
        String rejectReason,
        int pendingReturnQuantity,
        LocalDateTime returnRequestedAt,
        LocalDateTime returnedAt
) {
    /**
     * OrderItem 엔티티로부터 응답 DTO를 생성한다.
     *
     * <p>status 관련 필드를 {@link OrderItemStatus}에서 추출하여
     * API 소비자가 상태 enum을 직접 참조하지 않아도 되도록 한다.
     * 이는 REST API와 Thymeleaf SSR 양쪽에서 동일하게 사용 가능하다.</p>
     */
    public static OrderItemResponse from(OrderItem item) {
        OrderItemStatus itemStatus = item.getStatus();
        return new OrderItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getProductName(),
                item.getQuantity(),
                item.getCancelledQuantity(),
                item.getReturnedQuantity(),
                item.getRemainingQuantity(),
                item.getUnitPrice(),
                item.getSubtotal(),
                item.getCancelledAmount(),
                item.getReturnedAmount(),
                itemStatus.name(),
                itemStatus.getLabel(),
                itemStatus.getBadgeClass(),
                item.getReturnReason(),
                item.getRejectReason(),
                item.getPendingReturnQuantity(),
                item.getReturnRequestedAt(),
                item.getReturnedAt()
        );
    }
}
