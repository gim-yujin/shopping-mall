package com.shop.domain.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockAdjustRequest(
        @NotNull(message = "수량은 필수입니다.")
        Integer amount,

        @NotBlank(message = "사유는 필수입니다.")
        String reason
) {
}
