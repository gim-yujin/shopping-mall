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

    /**
     * [Phase 18] 읽기 모델로부터 API 응답 DTO를 생성한다.
     * CQRS 분리 후 엔티티 없이 읽기 모델만으로 응답을 구성한다.
     */
    public static ProductSummaryResponse from(ProductListReadModel readModel) {
        return new ProductSummaryResponse(
                readModel.productId(),
                readModel.productName(),
                readModel.price(),
                readModel.originalPrice(),
                readModel.discountPercent(),
                readModel.thumbnailUrl(),
                readModel.ratingAvg(),
                readModel.reviewCount() != null ? readModel.reviewCount() : 0,
                readModel.salesCount() != null ? readModel.salesCount() : 0
        );
    }
}
