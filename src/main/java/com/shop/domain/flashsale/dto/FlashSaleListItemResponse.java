package com.shop.domain.flashsale.dto;

import com.shop.domain.flashsale.entity.FlashSaleStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record FlashSaleListItemResponse(
        Long flashSaleId,
        String title,
        FlashSaleStatus status,
        LocalDateTime startTime,
        LocalDateTime endTime,
        List<Item> items
) {
    public record Item(
            Long flashSaleItemId,
            Long productId,
            String productName,
            BigDecimal salePrice,
            BigDecimal originalPrice,
            int remainingApprox,
            int allocatedQuantity,
            int perUserLimit
    ) {
    }
}
