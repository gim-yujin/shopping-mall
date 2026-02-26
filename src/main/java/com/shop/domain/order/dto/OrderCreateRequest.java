package com.shop.domain.order.dto;

import com.shop.domain.order.validation.ValidPaymentMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Locale;

public record OrderCreateRequest(
    @NotBlank(message = "배송지를 입력해주세요.")
    String shippingAddress,

    @NotBlank(message = "수령인 이름을 입력해주세요.")
    String recipientName,

    @NotBlank(message = "수령인 연락처를 입력해주세요.")
    String recipientPhone,

    @ValidPaymentMethod
    String paymentMethod,

    BigDecimal shippingFee,

    Long userCouponId,

    @Min(value = 0, message = "포인트는 0 이상이어야 합니다.")
    Integer usePoints
) {
    public OrderCreateRequest {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            paymentMethod = "CARD";
        } else {
            paymentMethod = paymentMethod.trim().toUpperCase(Locale.ROOT);
        }
        if (shippingFee == null) shippingFee = BigDecimal.ZERO;
        if (usePoints == null) usePoints = 0;
    }
}
