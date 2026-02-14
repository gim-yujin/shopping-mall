package com.shop.domain.review.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Review 엔티티 단위 테스트 — incrementHelpful, update, 초기값
 */
class ReviewEntityUnitTest {

    @Test
    @DisplayName("incrementHelpful — helpfulCount 1씩 증가")
    void incrementHelpful_incrementsByOne() {
        Review review = new Review(1L, 1L, null, 5, "좋아요", "내용");

        assertThat(review.getHelpfulCount()).isEqualTo(0);

        review.incrementHelpful();
        assertThat(review.getHelpfulCount()).isEqualTo(1);

        review.incrementHelpful();
        assertThat(review.getHelpfulCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("update — 평점, 제목, 내용 변경 + updatedAt 갱신")
    void update_changesFieldsAndTimestamp() {
        Review review = new Review(1L, 1L, null, 3, "보통", "그저 그래요");

        review.update(5, "최고", "매우 만족합니다");

        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getTitle()).isEqualTo("최고");
        assertThat(review.getContent()).isEqualTo("매우 만족합니다");
        assertThat(review.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("생성자 — 초기값: helpfulCount=0, createdAt/updatedAt 설정")
    void constructor_setsDefaults() {
        Review review = new Review(1L, 2L, 10L, 4, "좋아요", "만족");

        assertThat(review.getProductId()).isEqualTo(1L);
        assertThat(review.getUserId()).isEqualTo(2L);
        assertThat(review.getOrderItemId()).isEqualTo(10L);
        assertThat(review.getHelpfulCount()).isEqualTo(0);
        assertThat(review.getCreatedAt()).isNotNull();
        assertThat(review.getUpdatedAt()).isNotNull();
    }
}
