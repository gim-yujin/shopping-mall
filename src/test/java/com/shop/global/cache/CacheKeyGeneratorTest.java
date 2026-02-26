package com.shop.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyGeneratorTest {

    @Test
    @DisplayName("동일 페이지여도 정렬이 다르면 캐시 키가 달라야 한다")
    void pageableKey_shouldIncludeSortToAvoidCollision() {
        PageRequest newest = PageRequest.of(0, 20, Sort.by("createdAt").descending());
        PageRequest priceAsc = PageRequest.of(0, 20, Sort.by("price").ascending());

        String newestKey = CacheKeyGenerator.pageableWithPrefix("keyword", newest);
        String priceAscKey = CacheKeyGenerator.pageableWithPrefix("keyword", priceAsc);

        assertThat(newestKey).isNotEqualTo(priceAscKey);
        assertThat(newestKey).contains("0:20:").contains("createdAt: DESC");
        assertThat(priceAscKey).contains("0:20:").contains("price: ASC");
    }
}
