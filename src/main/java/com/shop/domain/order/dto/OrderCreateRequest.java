package com.shop.domain.order.dto;

import com.shop.domain.order.validation.ValidPaymentMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 주문 생성 요청 DTO.
 *
 * [P1-6] cartItemIds 필드 추가: null 또는 비어있으면 장바구니 전체 주문(기존 동작),
 * 값이 있으면 해당 장바구니 항목만 선택 주문.
 */
public record OrderCreateRequest(
    @NotBlank(message = "배송지를 입력해주세요.")
    String shippingAddress,

    @NotBlank(message = "수령인 이름을 입력해주세요.")
    String recipientName,

    @NotBlank(message = "수령인 연락처를 입력해주세요.")
    String recipientPhone,

    @ValidPaymentMethod
    String paymentMethod,

    /**
     * 클라이언트 요청값은 신뢰하지 않으며, 배송비는 항상 서버 정책으로 재계산됩니다.
     */
    BigDecimal shippingFee,

    Long userCouponId,

    @Min(value = 0, message = "포인트는 0 이상이어야 합니다.")
    Integer usePoints,

    /**
     * [P1-6] 선택 주문할 장바구니 항목 ID 목록.
     * null 또는 빈 리스트이면 장바구니 전체를 주문한다 (기존 동작 호환).
     * Thymeleaf 폼에서 hidden input으로 전달됨.
     */
    List<Long> cartItemIds
) {
    public OrderCreateRequest {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CARD";
        } else {
            paymentMethod = paymentMethod.trim().toUpperCase(Locale.ROOT);
        }
        shippingFee = BigDecimal.ZERO;
        if (usePoints == null) usePoints = 0;
    }
}
