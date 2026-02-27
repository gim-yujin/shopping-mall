package com.shop.domain.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * [P1-6] 장바구니 상품 추가 요청 DTO.
 */
public record CartAddRequest(
        @NotNull(message = "상품 ID는 필수입니다.")
        Long productId,

        @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        int quantity
) {
    public CartAddRequest {
        if (quantity <= 0) quantity = 1;
    }
}
