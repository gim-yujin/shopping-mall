package com.shop.global.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class PagingParamsTest {

    @Test
    void normalizePage_shouldFallbackToZero_whenNegative() {
        assertThat(PagingParams.normalizePage(-1)).isEqualTo(0);
    }

    @Test
    void normalizeSize_shouldFallbackToDefault_whenBelowOne() {
        assertThat(PagingParams.normalizeSize(0)).isEqualTo(PagingParams.DEFAULT_SIZE);
    }

    @Test
    void normalizeSize_shouldClampToMax_whenTooLarge() {
        assertThat(PagingParams.normalizeSize(10_000)).isEqualTo(PagingParams.MAX_SIZE);
    }

    @Test
    void normalizeProductSort_shouldFallbackToDefault_whenUnknown() {
        assertThat(PagingParams.normalizeProductSort("hacked_column")).isEqualTo(PagingParams.DEFAULT_SORT);
    }

    @Test
    void toProductSort_shouldMapWhitelistedSort() {
        Sort sort = PagingParams.toProductSort("price_asc");

        assertThat(sort.getOrderFor("price")).isNotNull();
        assertThat(sort.getOrderFor("price").isAscending()).isTrue();
    }
}
