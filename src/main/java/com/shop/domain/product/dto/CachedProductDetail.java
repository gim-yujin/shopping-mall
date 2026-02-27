package com.shop.domain.product.dto;

import com.shop.domain.product.entity.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [P2-7] 상품 상세 캐시용 불변 DTO.
 *
 * 기존 문제:
 *   {@code @Cacheable}이 Product 엔티티를 Caffeine 캐시에 직접 저장했다.
 *   Caffeine은 in-process 캐시이므로, 같은 JVM 내 모든 요청이 동일한 객체 참조를 공유한다.
 *   만약 어떤 코드가 캐시된 Product 객체의 setter를 호출하면,
 *   다른 요청에서 조회한 데이터까지 오염된다.
 *
 *   현재는 readOnly=true 트랜잭션 + OSIV=off 설정 덕분에
 *   영속성 컨텍스트 밖에서 Dirty Checking이 발생하지 않아 실제 문제는 없었다.
 *   하지만 이는 "설정에 의존하는 안전성"이며,
 *   새로운 코드에서 실수로 캐시 객체를 수정하면 즉시 버그가 된다.
 *
 * 수정:
 *   불변 record를 캐시에 저장하여 근본적으로 변경 불가능하게 만든다.
 *   - JPA 엔티티의 Lazy 프록시(category 등)가 캐시에 저장되는 문제도 함께 해결
 *   - 캐시 직렬화(Redis 등 외부 캐시 전환 시) 호환성 확보
 *   - Thymeleaf 템플릿에서 record 컴포넌트를 프로퍼티처럼 접근 가능
 *
 * @see com.shop.domain.product.service.ProductService#findByIdCached
 */
public record CachedProductDetail(
        Long productId,
        String productName,
        String description,
        BigDecimal price,
        BigDecimal originalPrice,
        int discountPercent,
        Integer stockQuantity,
        Integer salesCount,
        Integer viewCount,
        BigDecimal ratingAvg,
        Integer reviewCount,
        Boolean isActive,
        String thumbnailUrl,
        Integer categoryId,
        String categoryName,
        LocalDateTime createdAt
) {
    /**
     * Product 엔티티로부터 캐시용 스냅샷을 생성한다.
     *
     * 이 시점에 Category Lazy 프록시를 초기화하여 (findByIdWithCategory JOIN FETCH 사용)
     * 캐시에 초기화되지 않은 프록시가 저장되는 것을 방지한다.
     */
    public static CachedProductDetail from(Product product) {
        return new CachedProductDetail(
                product.getProductId(),
                product.getProductName(),
                product.getDescription(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getDiscountPercent(),
                product.getStockQuantity(),
                product.getSalesCount(),
                product.getViewCount(),
                product.getRatingAvg(),
                product.getReviewCount(),
                product.getIsActive(),
                product.getThumbnailUrl(),
                product.getCategory() != null ? product.getCategory().getCategoryId() : null,
                product.getCategory() != null ? product.getCategory().getCategoryName() : null,
                product.getCreatedAt()
        );
    }
}
