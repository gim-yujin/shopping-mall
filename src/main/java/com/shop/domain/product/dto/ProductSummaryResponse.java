package com.shop.domain.product.dto;

import com.shop.domain.product.entity.Product;

import java.math.BigDecimal;

/**
 * [P1-6] 상품 목록 조회용 응답 DTO.
 * Entity를 직접 노출하지 않고, API 소비자에게 필요한 필드만 선별하여 전달한다.
 */
public record ProductSummaryResponse(
        Long productId,
        String productName,
        BigDecimal price,
        BigDecimal originalPrice,
        int discountPercent,
        String thumbnailUrl,
        BigDecimal ratingAvg,
        int reviewCount,
        int salesCount
) {
    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(
                product.getProductId(),
                product.getProductName(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getDiscountPercent(),
                product.getThumbnailUrl(),
                product.getRatingAvg(),
                product.getReviewCount() != null ? product.getReviewCount() : 0,
                product.getSalesCount() != null ? product.getSalesCount() : 0
        );
    }
}
