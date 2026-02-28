package com.shop.domain.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 반품 신청 요청 DTO.
 *
 * <p>[Step 2] returnReason 필드 추가.
 * 반품 사유를 필수 입력으로 받아 관리자가 승인/거절 판단에 활용한다.</p>
 *
 * @param orderItemId  반품할 주문상품 ID
 * @param quantity     반품 수량 (1 이상)
 * @param returnReason 반품 사유 (DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)
 */
public record ReturnRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) Integer quantity,
        @NotBlank String returnReason
) {
}
