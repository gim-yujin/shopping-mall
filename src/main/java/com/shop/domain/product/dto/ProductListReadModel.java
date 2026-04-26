package com.shop.domain.product.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * [Phase 18] 상품 목록 읽기 전용 모델 — CQRS 읽기 모델 분리.
 *
 * <h3>기존 문제</h3>
 * <p>상품 목록/검색/홈 페이지의 읽기 경로에서 JPA Product 엔티티를 그대로 사용했다.
 * 이로 인해 다음 문제가 발생했다:</p>
 * <ul>
 *   <li><b>불필요한 데이터 로딩</b>: 목록에 필요 없는 description, version, updatedAt 등
 *       전체 컬럼이 SELECT되어 네트워크/메모리 낭비</li>
 *   <li><b>Lazy 프록시 함정</b>: Category, ProductImage 등 연관 엔티티에 접근 시
 *       N+1 쿼리 발생. JOIN FETCH로 해결했지만 JPA 프록시 자체가 캐시에 저장됨</li>
 *   <li><b>Dirty Checking 오버헤드</b>: readOnly=true 트랜잭션에서도 영속성 컨텍스트가
 *       엔티티 스냅샷을 보관하여 GC 부담 증가</li>
 *   <li><b>썸네일 N+1</b>: Product.getThumbnailUrl()이 images 컬렉션을 초기화하여
 *       상품당 1개의 추가 쿼리 발생 (batch_fetch_size로 완화되지만 근본 해결 아님)</li>
 * </ul>
 *
 * <h3>해결: 읽기 전용 플랫 프로젝션</h3>
 * <p>네이티브 SQL로 필요한 컬럼만 SELECT하고, 썸네일을 서브쿼리로 한 번에 가져와
 * JPA 프록시/스냅샷 없이 불변 record에 직접 매핑한다.
 * 캐시에 저장해도 Lazy 초기화 문제가 없고, 객체 크기가 작아 힙 사용량이 줄어든다.</p>
 *
 * <h3>Thymeleaf 호환성</h3>
 * <p>Java record의 컴포넌트 접근자(productName() 등)는 Spring Boot 3.x의 Thymeleaf에서
 * 프로퍼티처럼 접근 가능하다 (${product.productName}). 기존 템플릿 변경 불필요.</p>
 */
public record ProductListReadModel(
        Long productId,
        String productName,
        BigDecimal price,
        BigDecimal originalPrice,
        int discountPercent,
        BigDecimal ratingAvg,
        Integer reviewCount,
        Integer salesCount,
        String thumbnailUrl,
        Integer categoryId,
        String categoryName,
        LocalDateTime createdAt,
        Boolean isActive,
        Integer stockQuantity
) {
    public boolean soldOut() {
        return stockQuantity != null && stockQuantity <= 0;
    }

    /**
     * 네이티브 SQL 결과(Object[])로부터 읽기 모델을 생성한다.
     *
     * <p>DB에서 할인율을 계산하지 않고 Java에서 계산하는 이유:
     * Product 엔티티의 getDiscountPercent() 로직과 정확히 동일한 결과를 보장하기 위함.
     * DB의 FLOOR 연산과 Java의 RoundingMode.FLOOR가 부동소수점 차이로 1% 오차가
     * 발생할 수 있으므로, 단일 소스(Java)에서 계산한다.</p>
     *
     * @param columns v_product_list 뷰의 컬럼 순서와 일치하는 배열
     */
    public static ProductListReadModel fromNativeRow(Object[] columns) {
        Long productId = ((Number) columns[0]).longValue();
        String productName = (String) columns[1];
        BigDecimal price = (BigDecimal) columns[2];
        BigDecimal originalPrice = (BigDecimal) columns[3];
        BigDecimal ratingAvg = (BigDecimal) columns[4];
        Integer reviewCount = columns[5] != null ? ((Number) columns[5]).intValue() : 0;
        Integer salesCount = columns[6] != null ? ((Number) columns[6]).intValue() : 0;
        Integer categoryId = columns[7] != null ? ((Number) columns[7]).intValue() : null;
        String categoryName = (String) columns[8];
        // [Phase 18] 네이티브 SQL은 java.sql.Timestamp를 반환하므로 LocalDateTime으로 변환한다.
        // JPQL은 Hibernate가 자동 변환하지만, 네이티브 쿼리는 JDBC 드라이버 타입 그대로 전달된다.
        LocalDateTime createdAt = columns[9] != null
                ? ((java.sql.Timestamp) columns[9]).toLocalDateTime()
                : null;
        String thumbnailUrl = (String) columns[10];
        Boolean isActive = (Boolean) columns[11];
        Integer stockQuantity = columns[12] != null ? ((Number) columns[12]).intValue() : null;

        int discountPercent = computeDiscountPercent(price, originalPrice);

        return new ProductListReadModel(
                productId, productName, price, originalPrice, discountPercent,
                ratingAvg, reviewCount, salesCount, thumbnailUrl,
                categoryId, categoryName, createdAt, isActive, stockQuantity
        );
    }

    /**
     * Product.getDiscountPercent()과 동일한 할인율 계산 로직.
     * 단일 소스를 유지하여 엔티티와 읽기 모델 간 불일치를 방지한다.
     */
    static int computeDiscountPercent(BigDecimal price, BigDecimal originalPrice) {
        if (originalPrice != null && originalPrice.compareTo(BigDecimal.ZERO) > 0
                && originalPrice.compareTo(price) > 0) {
            return originalPrice.subtract(price)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(originalPrice, 0, RoundingMode.FLOOR)
                    .intValue();
        }
        return 0;
    }
}
