package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.repository.ReviewHelpfulRepository;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewHelpfulRepository reviewHelpfulRepository;

    @Mock
    private ProductRepository productRepository;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, reviewHelpfulRepository, productRepository);
    }

    @Test
    @DisplayName("createReview - 같은 user/orderItem 중복 시 저장 없이 예외")
    void createReview_duplicateOrderItem_throwsBeforeSave() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "중복", "내용");

        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .as("동일 사용자/주문아이템 중복 리뷰는 차단되어야 함")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 리뷰를 작성");

        verify(reviewRepository, never()).save(any());
        verify(productRepository, never()).findById(any());
    }

    @Test
    @DisplayName("createReview - 평균 평점은 소수 둘째 자리 반올림으로 업데이트")
    void createReview_updatesProductRatingRoundedToTwoDecimals() {
        Long userId = 11L;
        Long productId = 101L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, null, 5, "좋음", "내용");
        Product product = mock(Product.class);

        when(reviewRepository.existsByUserIdAndOrderItemId(any(), any())).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findAverageRatingByProductId(productId)).thenReturn(Optional.of(4.666666));
        when(reviewRepository.countByProductId(productId)).thenReturn(3);

        reviewService.createReview(userId, request);

        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(product).updateRating(avgCaptor.capture(), eq(3));
        assertThat(avgCaptor.getValue())
                .as("평균 평점은 소수 둘째 자리 반올림 값이어야 함")
                .isEqualByComparingTo("4.67");
    }

    @Test
    @DisplayName("markHelpful - 기존 좋아요가 있으면 취소되고 false 반환")
    void markHelpful_deletePath_returnsFalseAndDecrements() {
        Long reviewId = 1L;
        Long userId = 2L;
        Review review = new Review(100L, 99L, null, 4, "t", "c");

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewHelpfulRepository.deleteByReviewIdAndUserIdNative(reviewId, userId)).thenReturn(1);

        boolean result = reviewService.markHelpful(reviewId, userId);

        assertThat(result)
                .as("기존 도움이 돼요가 있으면 토글 취소되어 false 반환")
                .isFalse();
        verify(reviewRepository).decrementHelpfulCount(reviewId);
        verify(reviewHelpfulRepository, never()).insertIgnoreConflict(any(), any());
    }

    @Test
    @DisplayName("markHelpful - 신규 추가 경로면 true 반환하고 카운트 증가")
    void markHelpful_insertPath_returnsTrueAndIncrements() {
        Long reviewId = 1L;
        Long userId = 2L;
        Review review = new Review(100L, 99L, null, 4, "t", "c");

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewHelpfulRepository.deleteByReviewIdAndUserIdNative(reviewId, userId)).thenReturn(0);
        when(reviewHelpfulRepository.insertIgnoreConflict(reviewId, userId)).thenReturn(1);

        boolean result = reviewService.markHelpful(reviewId, userId);

        assertThat(result)
                .as("신규 도움이 돼요 추가면 true 반환")
                .isTrue();
        verify(reviewRepository).incrementHelpfulCount(reviewId);
    }

    @Test
    @DisplayName("getHelpedReviewIds - userId null 또는 reviewIds 빈 값이면 즉시 빈 집합")
    void getHelpedReviewIds_returnsEmptyWithoutRepositoryCall() {
        assertThat(reviewService.getHelpedReviewIds(null, Set.of(1L)))
                .as("userId가 null이면 즉시 빈 집합 반환")
                .isEmpty();
        assertThat(reviewService.getHelpedReviewIds(1L, Set.of()))
                .as("reviewIds가 비어있으면 즉시 빈 집합 반환")
                .isEmpty();

        verifyNoInteractions(reviewHelpfulRepository);
    }
}
