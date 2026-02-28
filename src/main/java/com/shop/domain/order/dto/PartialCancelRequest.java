package com.shop.domain.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PartialCancelRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) Integer quantity
) {
}
