package com.shop.global.common;

import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * 페이지네이션/정렬 파라미터를 공통 정책으로 보정한다.
 * 정책: 잘못된 값은 400을 던지지 않고 안전한 기본값으로 대체한다.
 */
public final class PagingParams {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "best";

    private static final Set<String> ALLOWED_PRODUCT_SORTS = Set.of(
            "best", "price_asc", "price_desc", "newest", "rating", "review"
    );

    private PagingParams() {
    }

    public static int normalizePage(int page) {
        return Math.max(page, DEFAULT_PAGE);
    }

    public static int normalizeSize(int size) {
        return normalizeSize(size, DEFAULT_SIZE);
    }

    public static int normalizeSize(int size, int defaultSize) {
        if (size < 1) {
            return defaultSize;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static String normalizeProductSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }
        return ALLOWED_PRODUCT_SORTS.contains(sort) ? sort : DEFAULT_SORT;
    }

    public static Sort toProductSort(String sort) {
        String normalizedSort = normalizeProductSort(sort);

        return switch (normalizedSort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "newest" -> Sort.by("createdAt").descending();
            case "rating" -> Sort.by("ratingAvg").descending();
            case "review" -> Sort.by("reviewCount").descending();
            default -> Sort.by("salesCount").descending();
        };
    }

    /**
     * [Phase 18] 네이티브 SQL용 상품 정렬 — snake_case 컬럼명 사용.
     *
     * <p>문제: toProductSort()는 JPA 엔티티 필드명(camelCase: salesCount, createdAt)을 반환한다.
     * JPQL에서는 Hibernate가 이를 자동으로 DB 컬럼명으로 변환하지만,
     * 네이티브 SQL(@Query nativeQuery=true)에서는 Sort의 property가
     * 그대로 ORDER BY 절에 삽입되어 "column salesCount does not exist" 오류가 발생한다.</p>
     *
     * <p>해결: 네이티브 쿼리에서 사용하는 Pageable의 Sort는
     * DB 컬럼명(snake_case: sales_count, created_at)을 직접 지정한다.</p>
     */
    public static Sort toProductSortNative(String sort) {
        String normalizedSort = normalizeProductSort(sort);

        return switch (normalizedSort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "newest" -> Sort.by("created_at").descending();
            case "rating" -> Sort.by("rating_avg").descending();
            case "review" -> Sort.by("review_count").descending();
            default -> Sort.by("sales_count").descending();
        };
    }
}
