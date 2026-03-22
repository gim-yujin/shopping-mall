package com.shop.domain.review.service;

import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.review.dto.ReviewUpdateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.repository.ReviewHelpfulRepository;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService 분기 커버리지 보강 테스트.
 *
 * <p>기존 ReviewServiceUnitTest에서 다루지 않은 분기를 검증한다:
 * - updateReview: 정상 수정, 리뷰 미존재, 타인 리뷰 수정 시도
 * - getReviewForEdit: 정상 조회, 리뷰 미존재, 타인 리뷰 조회 시도
 * - getProductReviews: 캐시 키 생성 (productReviewCacheKey)
 * - getUserReviews: 페이징 조회
 * - bumpProductReviewVersion: 캐시 null 분기, non-CaffeineCache(synchronized) 분기
 * - getProductReviewVersion: 캐시 null 분기, version null 분기</p>
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceBranchTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ReviewHelpfulRepository reviewHelpfulRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductService productService;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private ReviewService reviewService;

    // ── updateReview ──

    @Nested
    @DisplayName("updateReview — 리뷰 수정")
    class UpdateReviewTests {

        @Test
        @DisplayName("정상 수정 — rating/title/content 변경 + 평점 재계산 + 캐시 무효화")
        void updateReview_success_updatesAndRecalculates() {
            // given: 기존 리뷰 (userId=5)
            Review review = new Review(1L, 5L, 10L, 3, "기존 제목", "기존 내용");
            when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));

            // 평점 재계산용 Mock
            Product product = mock(Product.class);
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(reviewRepository.findAverageRatingByProductId(1L)).thenReturn(Optional.of(4.5));
            when(reviewRepository.countByProductId(1L)).thenReturn(10);

            // when
            ReviewUpdateRequest request = new ReviewUpdateRequest(5, "수정 제목", "수정 내용");
            Review updated = reviewService.updateReview(100L, 5L, request);

            // then: 리뷰 내용 변경됨
            assertThat(updated.getRating()).isEqualTo(5);
            assertThat(updated.getTitle()).isEqualTo("수정 제목");
            // 상품 캐시 무효화 호출
            verify(productService).evictProductDetailCache(1L);
        }

        @Test
        @DisplayName("리뷰 미존재 → ResourceNotFoundException")
        void updateReview_notFound_throwsException() {
            // given: 존재하지 않는 리뷰 ID
            when(reviewRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> reviewService.updateReview(999L, 5L,
                    new ReviewUpdateRequest(4, "제목", "내용")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("타인 리뷰 수정 시도 → BusinessException")
        void updateReview_notOwner_throwsException() {
            // given: 다른 사용자(userId=5)의 리뷰를 userId=99가 수정 시도
            Review review = new Review(1L, 5L, 10L, 3, "제목", "내용");
            when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));

            // when & then: 소유자 검증 실패
            assertThatThrownBy(() -> reviewService.updateReview(100L, 99L,
                    new ReviewUpdateRequest(4, "제목", "내용")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("본인의 리뷰만 수정");
        }
    }

    // ── getReviewForEdit ──

    @Nested
    @DisplayName("getReviewForEdit — 수정 폼용 단건 조회")
    class GetReviewForEditTests {

        @Test
        @DisplayName("정상 조회 — 소유자 본인의 리뷰 반환")
        void getReviewForEdit_success() {
            // given
            Review review = new Review(1L, 5L, 10L, 4, "제목", "내용");
            when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));

            // when
            Review result = reviewService.getReviewForEdit(100L, 5L);

            // then
            assertThat(result).isSameAs(review);
        }

        @Test
        @DisplayName("리뷰 미존재 → ResourceNotFoundException")
        void getReviewForEdit_notFound() {
            when(reviewRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.getReviewForEdit(999L, 5L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("타인 리뷰 조회 시도 → BusinessException")
        void getReviewForEdit_notOwner() {
            Review review = new Review(1L, 5L, 10L, 4, "제목", "내용");
            when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));

            assertThatThrownBy(() -> reviewService.getReviewForEdit(100L, 99L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("본인의 리뷰만 수정");
        }
    }

    // ── getUserReviews ──

    @Test
    @DisplayName("getUserReviews — 사용자별 리뷰 페이징 조회")
    void getUserReviews_delegatesToRepository() {
        // given
        Page<Review> emptyPage = new PageImpl<>(Collections.emptyList());
        when(reviewRepository.findByUserIdOrderByCreatedAtDesc(5L, PageRequest.of(0, 10)))
                .thenReturn(emptyPage);

        // when
        Page<Review> result = reviewService.getUserReviews(5L, PageRequest.of(0, 10));

        // then: Repository에 정확히 위임
        assertThat(result).isSameAs(emptyPage);
        verify(reviewRepository).findByUserIdOrderByCreatedAtDesc(5L, PageRequest.of(0, 10));
    }

    // ── bumpProductReviewVersion: 캐시 null / non-CaffeineCache 분기 ──

    @Test
    @DisplayName("deleteReview — 캐시가 null이면 bumpProductReviewVersion이 조용히 건너뛴다")
    void deleteReview_nullCache_skipsVersionBump() {
        // given: productReviewVersion 캐시가 null인 환경
        // bumpProductReviewVersion에서 cache == null → return 분기
        Review review = new Review(1L, 5L, 10L, 4, "제목", "내용");
        when(reviewRepository.findById(100L)).thenReturn(Optional.of(review));
        when(cacheManager.getCache("productReviewVersion")).thenReturn(null);

        // 평점 재계산용 Mock
        Product product = mock(Product.class);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(reviewRepository.findAverageRatingByProductId(1L)).thenReturn(Optional.empty());
        when(reviewRepository.countByProductId(1L)).thenReturn(0);

        // when: 삭제 실행 — 캐시 null이어도 예외 없이 완료
        reviewService.deleteReview(100L, 5L);

        // then
        verify(reviewRepository).delete(review);
    }

    @Test
    @DisplayName("productReviewCacheKey — 캐시가 null이면 버전 0 사용")
    void productReviewCacheKey_nullCache_usesVersionZero() {
        // given: productReviewVersion 캐시가 null
        // getProductReviewVersion에서 cache == null → 0L 반환
        when(cacheManager.getCache("productReviewVersion")).thenReturn(null);

        // when
        String key = reviewService.productReviewCacheKey(1L, PageRequest.of(0, 10));

        // then: 버전 0이 키에 포함
        assertThat(key).contains("v0");
    }

    @Test
    @DisplayName("productReviewCacheKey — 캐시에 버전이 없으면 0 사용")
    void productReviewCacheKey_nullVersion_usesVersionZero() {
        // given: 캐시는 있지만 해당 상품의 버전이 저장되지 않은 경우
        // getProductReviewVersion에서 version == null → 0L 반환
        ConcurrentMapCache cache = new ConcurrentMapCache("productReviewVersion");
        when(cacheManager.getCache("productReviewVersion")).thenReturn(cache);

        // when
        String key = reviewService.productReviewCacheKey(1L, PageRequest.of(0, 10));

        // then
        assertThat(key).contains("v0");
    }

    @Test
    @DisplayName("productReviewCacheKey — 캐시에 버전이 있으면 해당 버전 사용")
    void productReviewCacheKey_existingVersion_usesStoredVersion() {
        // given: 캐시에 버전 5가 저장된 상태
        ConcurrentMapCache cache = new ConcurrentMapCache("productReviewVersion");
        cache.put(1L, 5L);
        when(cacheManager.getCache("productReviewVersion")).thenReturn(cache);

        // when
        String key = reviewService.productReviewCacheKey(1L, PageRequest.of(0, 10));

        // then
        assertThat(key).contains("v5");
    }
}
