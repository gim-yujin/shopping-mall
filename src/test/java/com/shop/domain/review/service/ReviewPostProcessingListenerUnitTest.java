package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.global.event.ReviewRatingChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewPostProcessingListenerUnitTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ProductService productService;

    @Mock
    private CacheManager cacheManager;

    private ReviewPostProcessingListener listener;

    @BeforeEach
    void setUp() {
        listener = new ReviewPostProcessingListener(
                productRepository, reviewRepository, productService, cacheManager);
    }

    @Test
    @DisplayName("handleReviewRatingChanged - 평균 평점 소수 둘째 자리 반올림으로 업데이트")
    void handleReviewRatingChanged_updatesRatingRoundedToTwoDecimals() {
        Long productId = 101L;
        Product product = mock(Product.class);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findRatingStatsByProductId(productId))
                .thenReturn(Collections.singletonList(new Object[]{4.666666, 3L}));

        listener.handleReviewRatingChanged(new ReviewRatingChangedEvent(productId));

        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(product).updateRating(avgCaptor.capture(), eq(3));
        assertThat(avgCaptor.getValue())
                .as("평균 평점은 소수 둘째 자리 반올림 값이어야 함")
                .isEqualByComparingTo("4.67");
        verify(productService).evictProductDetailCache(productId);
    }

    @Test
    @DisplayName("handleReviewRatingChanged - 평균값이 없으면 0.00으로 갱신")
    void handleReviewRatingChanged_whenNoAverage_updatesZeroRating() {
        Long productId = 101L;
        Product product = mock(Product.class);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findRatingStatsByProductId(productId))
                .thenReturn(Collections.singletonList(new Object[]{null, 0L}));

        listener.handleReviewRatingChanged(new ReviewRatingChangedEvent(productId));

        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(product).updateRating(avgCaptor.capture(), eq(0));
        assertThat(avgCaptor.getValue())
                .as("리뷰 평균값이 없으면 기본 평점 0.00으로 갱신되어야 함")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("handleReviewRatingChanged - 상품 상세 캐시 무효화 호출")
    void handleReviewRatingChanged_evictsProductDetailCache() {
        Long productId = 101L;
        Product product = mock(Product.class);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findRatingStatsByProductId(productId))
                .thenReturn(Collections.singletonList(new Object[]{4.5, 2L}));

        listener.handleReviewRatingChanged(new ReviewRatingChangedEvent(productId));

        verify(productService).evictProductDetailCache(productId);
    }

    @Test
    @DisplayName("handleReviewRatingChanged - 예외 발생 시 로그만 남기고 전파하지 않음")
    void handleReviewRatingChanged_exceptionDoesNotPropagate() {
        Long productId = 999L;

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // 예외가 전파되지 않아야 함
        listener.handleReviewRatingChanged(new ReviewRatingChangedEvent(productId));

        verify(productService, never()).evictProductDetailCache(any());
    }
}
