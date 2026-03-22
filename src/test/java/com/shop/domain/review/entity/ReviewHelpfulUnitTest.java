package com.shop.domain.review.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReviewHelpful 엔티티 단위 테스트.
 *
 * <p>ReviewHelpful은 리뷰 "도움이 돼요" 기록을 나타내는 불변 엔티티이다.
 * 생성자에서 reviewId, userId, createdAt을 설정하며 이후 변경 불가.
 * 기존 테스트에서 한 번도 직접 인스턴스를 생성하지 않아 LINE 10%였다.</p>
 */
class ReviewHelpfulUnitTest {

    @Test
    @DisplayName("생성자가 모든 필드를 올바르게 초기화한다")
    void constructor_initializesAllFields() {
        // given & when: 리뷰 ID 10, 사용자 ID 5로 도움 기록 생성
        ReviewHelpful helpful = new ReviewHelpful(10L, 5L);

        // then: 모든 필드가 설정되고 createdAt이 자동 생성
        assertThat(helpful.getReviewId()).isEqualTo(10L);
        assertThat(helpful.getUserId()).isEqualTo(5L);
        assertThat(helpful.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("helpfulId는 JPA가 할당하므로 초기값은 null이다")
    void helpfulId_isNullBeforePersist() {
        // given & when
        ReviewHelpful helpful = new ReviewHelpful(1L, 2L);

        // then: @GeneratedValue이므로 persist 전에는 null
        assertThat(helpful.getHelpfulId()).isNull();
    }
}
