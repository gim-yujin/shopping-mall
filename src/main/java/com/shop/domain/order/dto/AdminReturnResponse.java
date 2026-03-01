package com.shop.domain.order.dto;

import java.time.LocalDateTime;

/**
 * 관리자 반품 대기 목록 응답 DTO.
 *
 * <h3>[Step 3] 관리자 반품 관리 페이지용 DTO</h3>
 *
 * <p><b>설계 의도:</b> 관리자 반품 관리 페이지에서 필요한 정보만 투영(projection)하여
 * 불필요한 데이터 전송을 방지한다. Order 전체를 로드하는 대신 JPQL 생성자 표현식으로
 * 필요한 필드만 조회한다.</p>
 *
 * <p><b>포함 정보:</b>
 * <ul>
 *   <li>주문 정보: orderId, orderNumber (주문 식별 + 상세 링크)</li>
 *   <li>아이템 정보: orderItemId, productName (반품 대상 식별)</li>
 *   <li>반품 정보: quantity, returnReason, returnRequestedAt (승인/거절 판단 근거)</li>
 *   <li>사용자 정보: userName, userEmail (고객 식별 + 연락)</li>
 * </ul>
 * </p>
 *
 * <p>이 DTO는 {@code OrderItemRepository.findReturnRequested()} 쿼리에서
 * 직접 생성되거나, 서비스 계층에서 엔티티를 변환하여 생성된다.</p>
 *
 * @param orderId            주문 ID
 * @param orderNumber        주문 번호 (표시용)
 * @param orderItemId        주문상품 ID (승인/거절 시 사용)
 * @param productName        상품명
 * @param quantity           반품 신청 수량 (pendingReturnQuantity)
 * @param returnReason       반품 사유 (DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)
 * @param returnRequestedAt  반품 신청 일시
 * @param userName           사용자 이름
 * @param userEmail          사용자 이메일
 */
public record AdminReturnResponse(
        Long orderId,
        String orderNumber,
        Long orderItemId,
        String productName,
        int quantity,
        String returnReason,
        LocalDateTime returnRequestedAt,
        String userName,
        String userEmail
) {
    /**
     * 반품 사유 코드를 한글 레이블로 변환한다.
     *
     * <p>관리자 UI에서 사유 코드 대신 읽기 좋은 한글 레이블을 표시하기 위한
     * 편의 메서드이다. 알 수 없는 코드는 원문 그대로 반환한다.</p>
     */
    public String getReturnReasonLabel() {
        if (returnReason == null) return "";
        return switch (returnReason) {
            case "DEFECT" -> "상품 불량/파손";
            case "WRONG_ITEM" -> "오배송";
            case "CHANGE_OF_MIND" -> "단순 변심";
            case "SIZE_ISSUE" -> "사이즈 불일치";
            case "OTHER" -> "기타";
            default -> returnReason;
        };
    }
}
