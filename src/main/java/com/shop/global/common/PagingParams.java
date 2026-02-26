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
}
