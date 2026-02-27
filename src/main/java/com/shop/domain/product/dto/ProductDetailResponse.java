package com.shop.domain.product.dto;

import com.shop.domain.product.entity.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [P1-6] 상품 상세 조회용 응답 DTO.
 * 재고 수량은 API 소비자에게 "구매 가능 여부"만 제공하고 정확한 수치는 숨긴다.
 * 정확한 재고 수량은 관리자 전용 API에서만 노출한다.
 */
public record ProductDetailResponse(
        Long productId,
        String productName,
        String description,
        BigDecimal price,
        BigDecimal originalPrice,
        int discountPercent,
        String thumbnailUrl,
        String categoryName,
        Integer categoryId,
        boolean inStock,
        BigDecimal ratingAvg,
        int reviewCount,
        int salesCount,
        int viewCount,
        LocalDateTime createdAt
) {
    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getProductId(),
                product.getProductName(),
                product.getDescription(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getDiscountPercent(),
                product.getThumbnailUrl(),
                product.getCategory() != null ? product.getCategory().getCategoryName() : null,
                product.getCategory() != null ? product.getCategory().getCategoryId() : null,
                product.getStockQuantity() != null && product.getStockQuantity() > 0,
                product.getRatingAvg(),
                product.getReviewCount() != null ? product.getReviewCount() : 0,
                product.getSalesCount() != null ? product.getSalesCount() : 0,
                product.getViewCount() != null ? product.getViewCount() : 0,
                product.getCreatedAt()
        );
    }

    /**
     * [P2-7] 캐시 DTO로부터 API 응답을 생성한다.
     * findByIdCached()가 CachedProductDetail을 반환하므로 이 팩토리를 추가.
     */
    public static ProductDetailResponse from(CachedProductDetail cached) {
        return new ProductDetailResponse(
                cached.productId(),
                cached.productName(),
                cached.description(),
                cached.price(),
                cached.originalPrice(),
                cached.discountPercent(),
                cached.thumbnailUrl(),
                cached.categoryName(),
                cached.categoryId(),
                cached.stockQuantity() != null && cached.stockQuantity() > 0,
                cached.ratingAvg(),
                cached.reviewCount() != null ? cached.reviewCount() : 0,
                cached.salesCount() != null ? cached.salesCount() : 0,
                cached.viewCount() != null ? cached.viewCount() : 0,
                cached.createdAt()
        );
    }
}
