package com.shop.domain.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * [P1-6] 장바구니 전체 조회 응답 DTO.
 * 항목 목록과 합계 금액을 단일 응답으로 제공한다.
 */
public record CartResponse(
        List<CartItemResponse> items,
        BigDecimal totalPrice,
        int itemCount
) {
}
