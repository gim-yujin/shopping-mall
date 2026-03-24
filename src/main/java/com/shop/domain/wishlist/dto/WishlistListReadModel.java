package com.shop.domain.wishlist.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [Phase 22] 위시리스트 목록 읽기 전용 모델 — CQRS 읽기 모델 분리.
 *
 * <p>v_wishlist_list 뷰(wishlists JOIN products + 썸네일 서브쿼리)를
 * 네이티브 SQL로 조회하여 JPA 프록시 없이 불변 record에 직접 매핑한다.
 * 기존 Wishlist 엔티티 + Hibernate.initialize(images) 우회 패턴을 제거하고
 * 썸네일 URL을 서브쿼리로 한 번에 가져온다.</p>
 */
public record WishlistListReadModel(
        Long wishlistId,
        Long userId,
        Long productId,
        String productName,
        BigDecimal price,
        BigDecimal originalPrice,
        String thumbnailUrl,
        boolean inStock,
        LocalDateTime addedAt
) {
    /**
     * v_wishlist_list 네이티브 SQL 결과(Object[])로부터 읽기 모델을 생성한다.
     *
     * @param columns v_wishlist_list 뷰의 컬럼 순서와 일치하는 배열
     */
    public static WishlistListReadModel fromNativeRow(Object[] columns) {
        Long wishlistId = ((Number) columns[0]).longValue();
        Long userId = ((Number) columns[1]).longValue();
        Long productId = ((Number) columns[2]).longValue();
        String productName = (String) columns[3];
        BigDecimal price = (BigDecimal) columns[4];
        BigDecimal originalPrice = (BigDecimal) columns[5];
        String thumbnailUrl = (String) columns[6];
        Integer stockQuantity = columns[7] != null ? ((Number) columns[7]).intValue() : 0;
        LocalDateTime addedAt = columns[8] != null
                ? ((java.sql.Timestamp) columns[8]).toLocalDateTime()
                : null;

        return new WishlistListReadModel(
                wishlistId, userId, productId, productName,
                price, originalPrice, thumbnailUrl,
                stockQuantity > 0, addedAt
        );
    }
}
