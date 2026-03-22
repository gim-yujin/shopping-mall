package com.shop.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PageResponse record 단위 테스트.
 *
 * <p>PageResponse.of(Page) 팩토리 메서드와 record 필드 접근자를 검증한다.
 * 기존 테스트에서 PageResponse를 직접 생성하지 않아 LINE 0% / METHOD 0%였다.</p>
 */
class PageResponseTest {

    @Test
    @DisplayName("of(Page) — Page 객체의 모든 속성이 정확히 매핑된다")
    void of_mapsAllPageAttributes() {
        // given: 3페이지 중 2번째 페이지 (0-indexed: page=1), 크기 10
        Page<String> page = new PageImpl<>(
                List.of("상품A", "상품B", "상품C"),
                PageRequest.of(1, 10),
                30 // totalElements
        );

        // when
        PageResponse<String> response = PageResponse.of(page);

        // then: 모든 필드가 Page 객체와 일치
        assertThat(response.content()).containsExactly("상품A", "상품B", "상품C");
        assertThat(response.currentPage()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(3); // ceil(30/10)
        assertThat(response.totalElements()).isEqualTo(30);
        assertThat(response.pageSize()).isEqualTo(10);
        assertThat(response.hasNext()).isTrue();     // page 1 of 3
        assertThat(response.hasPrevious()).isTrue();  // page 1 > 0
    }

    @Test
    @DisplayName("of(Page) — 첫 페이지는 hasPrevious=false")
    void of_firstPage_hasPreviousFalse() {
        // given: 첫 번째 페이지
        Page<Integer> page = new PageImpl<>(
                List.of(1, 2),
                PageRequest.of(0, 5),
                10
        );

        // when
        PageResponse<Integer> response = PageResponse.of(page);

        // then
        assertThat(response.currentPage()).isEqualTo(0);
        assertThat(response.hasPrevious()).isFalse();
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("of(Page) — 마지막 페이지는 hasNext=false")
    void of_lastPage_hasNextFalse() {
        // given: 마지막 페이지
        Page<Integer> page = new PageImpl<>(
                List.of(9, 10),
                PageRequest.of(4, 2),
                10
        );

        // when
        PageResponse<Integer> response = PageResponse.of(page);

        // then
        assertThat(response.hasNext()).isFalse();
        assertThat(response.hasPrevious()).isTrue();
    }
}
