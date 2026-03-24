package com.shop.domain.wishlist.dto;

import com.shop.domain.wishlist.entity.Wishlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [P1-6] 위시리스트 항목 응답 DTO.
 */
public record WishlistItemResponse(
        Long wishlistId,
        Long productId,
        String productName,
        BigDecimal price,
        String thumbnailUrl,
        boolean inStock,
        LocalDateTime addedAt
) {
    public static WishlistItemResponse from(Wishlist wishlist) {
        return new WishlistItemResponse(
                wishlist.getWishlistId(),
                wishlist.getProduct().getProductId(),
                wishlist.getProduct().getProductName(),
                wishlist.getProduct().getPrice(),
                wishlist.getProduct().getThumbnailUrl(),
                wishlist.getProduct().getStockQuantity() != null
                        && wishlist.getProduct().getStockQuantity() > 0,
                wishlist.getCreatedAt()
        );
    }

    /**
     * [Phase 22] CQRS 읽기 모델에서 응답 DTO 변환.
     * Hibernate 프록시 없이 플랫 데이터에서 직접 매핑한다.
     */
    public static WishlistItemResponse from(WishlistListReadModel readModel) {
        return new WishlistItemResponse(
                readModel.wishlistId(),
                readModel.productId(),
                readModel.productName(),
                readModel.price(),
                readModel.thumbnailUrl(),
                readModel.inStock(),
                readModel.addedAt()
        );
    }
}
