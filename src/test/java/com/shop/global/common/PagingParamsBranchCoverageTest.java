package com.shop.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PagingParams 분기 커버리지 보강 테스트.
 *
 * <p>기존 PagingParamsTest에서 다루지 않은 분기를 검증한다:
 * - normalizeSize: 유효 범위 내 값, 커스텀 기본값
 * - normalizeProductSort: null/빈 문자열, 유효한 정렬 옵션
 * - toProductSort/toProductSortNative: 모든 정렬 옵션의 매핑
 * - normalizePage: 양수 값 통과</p>
 */
class PagingParamsBranchCoverageTest {

    // ── normalizePage ──

    @Nested
    @DisplayName("normalizePage — 페이지 번호 보정")
    class NormalizePageTest {

        @Test
        @DisplayName("양수 페이지는 그대로 반환된다")
        void positivePage_passedThrough() {
            assertThat(PagingParams.normalizePage(5)).isEqualTo(5);
        }

        @Test
        @DisplayName("0은 그대로 반환된다")
        void zeroPage_passedThrough() {
            assertThat(PagingParams.normalizePage(0)).isEqualTo(0);
        }
    }

    // ── normalizeSize ──

    @Nested
    @DisplayName("normalizeSize — 페이지 크기 보정")
    class NormalizeSizeTest {

        @Test
        @DisplayName("유효 범위 내 크기는 그대로 반환된다")
        void validSize_passedThrough() {
            assertThat(PagingParams.normalizeSize(50)).isEqualTo(50);
        }

        @Test
        @DisplayName("커스텀 기본값을 사용하는 오버로드 — 크기 0이면 커스텀 기본값 반환")
        void customDefault_usedWhenSizeBelowOne() {
            // normalizeSize(size, defaultSize) 오버로드 테스트
            assertThat(PagingParams.normalizeSize(0, 30)).isEqualTo(30);
        }

        @Test
        @DisplayName("커스텀 기본값 오버로드 — 유효 범위 내 크기는 그대로 반환")
        void customDefault_validSizePassedThrough() {
            assertThat(PagingParams.normalizeSize(15, 30)).isEqualTo(15);
        }

        @Test
        @DisplayName("MAX_SIZE 초과 시 MAX_SIZE로 제한된다")
        void exceedsMax_clampedToMax() {
            assertThat(PagingParams.normalizeSize(200, 30)).isEqualTo(PagingParams.MAX_SIZE);
        }
    }

    // ── normalizeProductSort ──

    @Nested
    @DisplayName("normalizeProductSort — 상품 정렬 옵션 보정")
    class NormalizeProductSortTest {

        @Test
        @DisplayName("null이면 기본값 'best' 반환")
        void nullSort_returnsDefault() {
            assertThat(PagingParams.normalizeProductSort(null)).isEqualTo("best");
        }

        @Test
        @DisplayName("빈 문자열이면 기본값 반환")
        void blankSort_returnsDefault() {
            assertThat(PagingParams.normalizeProductSort("  ")).isEqualTo("best");
        }

        @Test
        @DisplayName("허용된 정렬 옵션은 그대로 반환된다")
        void allowedSorts_passedThrough() {
            // 화이트리스트의 모든 옵션을 검증
            assertThat(PagingParams.normalizeProductSort("price_asc")).isEqualTo("price_asc");
            assertThat(PagingParams.normalizeProductSort("price_desc")).isEqualTo("price_desc");
            assertThat(PagingParams.normalizeProductSort("newest")).isEqualTo("newest");
            assertThat(PagingParams.normalizeProductSort("rating")).isEqualTo("rating");
            assertThat(PagingParams.normalizeProductSort("review")).isEqualTo("review");
            assertThat(PagingParams.normalizeProductSort("best")).isEqualTo("best");
        }
    }

    // ── toProductSort (JPA용 camelCase) ──

    @Nested
    @DisplayName("toProductSort — JPA 엔티티 필드 기반 Sort 매핑")
    class ToProductSortTest {

        @Test
        @DisplayName("price_desc → price 내림차순")
        void priceDesc() {
            Sort sort = PagingParams.toProductSort("price_desc");
            assertThat(sort.getOrderFor("price")).isNotNull();
            assertThat(sort.getOrderFor("price").isDescending()).isTrue();
        }

        @Test
        @DisplayName("newest → createdAt 내림차순")
        void newest() {
            Sort sort = PagingParams.toProductSort("newest");
            assertThat(sort.getOrderFor("createdAt")).isNotNull();
            assertThat(sort.getOrderFor("createdAt").isDescending()).isTrue();
        }

        @Test
        @DisplayName("rating → ratingAvg 내림차순")
        void rating() {
            Sort sort = PagingParams.toProductSort("rating");
            assertThat(sort.getOrderFor("ratingAvg")).isNotNull();
        }

        @Test
        @DisplayName("review → reviewCount 내림차순")
        void review() {
            Sort sort = PagingParams.toProductSort("review");
            assertThat(sort.getOrderFor("reviewCount")).isNotNull();
        }

        @Test
        @DisplayName("best(기본값) → salesCount 내림차순")
        void best_default() {
            Sort sort = PagingParams.toProductSort("best");
            assertThat(sort.getOrderFor("salesCount")).isNotNull();
            assertThat(sort.getOrderFor("salesCount").isDescending()).isTrue();
        }
    }

    // ── toProductSortNative (네이티브 SQL용 snake_case) ──

    @Nested
    @DisplayName("toProductSortNative — 네이티브 SQL 컬럼명 기반 Sort 매핑")
    class ToProductSortNativeTest {

        @Test
        @DisplayName("price_asc → price 오름차순")
        void priceAsc() {
            Sort sort = PagingParams.toProductSortNative("price_asc");
            assertThat(sort.getOrderFor("price")).isNotNull();
            assertThat(sort.getOrderFor("price").isAscending()).isTrue();
        }

        @Test
        @DisplayName("price_desc → price 내림차순")
        void priceDesc() {
            Sort sort = PagingParams.toProductSortNative("price_desc");
            assertThat(sort.getOrderFor("price")).isNotNull();
            assertThat(sort.getOrderFor("price").isDescending()).isTrue();
        }

        @Test
        @DisplayName("newest → created_at 내림차순 (snake_case)")
        void newest() {
            Sort sort = PagingParams.toProductSortNative("newest");
            // JPA용 toProductSort는 createdAt, 네이티브용은 created_at
            assertThat(sort.getOrderFor("created_at")).isNotNull();
        }

        @Test
        @DisplayName("rating → rating_avg 내림차순 (snake_case)")
        void rating() {
            Sort sort = PagingParams.toProductSortNative("rating");
            assertThat(sort.getOrderFor("rating_avg")).isNotNull();
        }

        @Test
        @DisplayName("review → review_count 내림차순 (snake_case)")
        void review() {
            Sort sort = PagingParams.toProductSortNative("review");
            assertThat(sort.getOrderFor("review_count")).isNotNull();
        }

        @Test
        @DisplayName("best(기본값) → sales_count 내림차순 (snake_case)")
        void best_default() {
            Sort sort = PagingParams.toProductSortNative("best");
            assertThat(sort.getOrderFor("sales_count")).isNotNull();
        }
    }
}
