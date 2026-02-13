package com.shop.domain.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewCreateRequest(
    @NotNull Long productId,
    Long orderItemId,
    @Min(1) @Max(5) int rating,
    String title,
    String content
) {}
