package com.shop.domain.order.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record OrderCreateRequest(
    @NotBlank(message = "배송지를 입력해주세요.")
    String shippingAddress,

    @NotBlank(message = "수령인 이름을 입력해주세요.")
    String recipientName,

    @NotBlank(message = "수령인 연락처를 입력해주세요.")
    String recipientPhone,

    String paymentMethod,

    BigDecimal shippingFee,

    Long userCouponId
) {
    public OrderCreateRequest {
        if (paymentMethod == null || paymentMethod.isBlank()) paymentMethod = "CARD";
        if (shippingFee == null) shippingFee = BigDecimal.ZERO;
    }
}
