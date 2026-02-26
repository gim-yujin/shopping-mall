package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.repository.ReviewHelpfulRepository;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
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

    @Mock
    private ProductService productService;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CacheManager cacheManager;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(
                reviewRepository,
                reviewHelpfulRepository,
                productRepository,
                productService,
                orderItemRepository,
                cacheManager);
    }

    private void mockValidDeliveredOrderItem(Long orderItemId, Long userId, Long productId) {
        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(orderItemRepository.findById(orderItemId)).thenReturn(Optional.of(orderItem));
        when(orderItem.getOrder()).thenReturn(order);
        when(order.getUserId()).thenReturn(userId);
        when(orderItem.getProductId()).thenReturn(productId);
        when(order.getOrderStatus()).thenReturn(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("createReview - 같은 user/orderItem 중복 시 저장 없이 예외")
    void createReview_duplicateOrderItem_throwsBeforeSave() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "중복", "내용");

        mockValidDeliveredOrderItem(orderItemId, userId, productId);

        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .as("동일 사용자/주문아이템 중복 리뷰는 차단되어야 함")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 리뷰를 작성");

        verify(reviewRepository, never()).save(any());
        verify(productRepository, never()).findById(any());
        verifyNoInteractions(productService);
    }

    @Test
    @DisplayName("createReview - 저장 시점 unique 충돌도 DUPLICATE_REVIEW로 변환")
    void createReview_duplicateAtDatabaseLevel_throwsBusinessException() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "중복", "내용");

        mockValidDeliveredOrderItem(orderItemId, userId, productId);
        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 리뷰를 작성");

        verify(productRepository, never()).findById(any());
        verifyNoInteractions(productService);
    }

    @Test
    @DisplayName("createReview - 타인 주문 항목이면 예외")
    void createReview_orderItemOwnedByOtherUser_throwsException() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "권한", "내용");
        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(orderItemRepository.findById(orderItemId)).thenReturn(Optional.of(orderItem));
        when(orderItem.getOrder()).thenReturn(order);
        when(order.getUserId()).thenReturn(999L);

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인 주문");

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReview - 주문 상품과 요청 상품이 다르면 예외")
    void createReview_orderItemProductMismatch_throwsException() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "불일치", "내용");
        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(orderItemRepository.findById(orderItemId)).thenReturn(Optional.of(orderItem));
        when(orderItem.getOrder()).thenReturn(order);
        when(order.getUserId()).thenReturn(userId);
        when(orderItem.getProductId()).thenReturn(202L);

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("일치하지 않습니다");

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReview - 배송 완료 전 주문이면 예외")
    void createReview_orderStatusNotDelivered_throwsException() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "미배송", "내용");
        Order order = mock(Order.class);
        OrderItem orderItem = mock(OrderItem.class);

        when(orderItemRepository.findById(orderItemId)).thenReturn(Optional.of(orderItem));
        when(orderItem.getOrder()).thenReturn(order);
        when(order.getUserId()).thenReturn(userId);
        when(orderItem.getProductId()).thenReturn(productId);
        when(order.getOrderStatus()).thenReturn(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> reviewService.createReview(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송 완료");

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReview - 배송 완료된 본인 주문 항목이면 생성 성공")
    void createReview_validOrderItem_succeeds() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "정상", "내용");
        Product product = mock(Product.class);

        mockValidDeliveredOrderItem(orderItemId, userId, productId);
        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findAverageRatingByProductId(productId)).thenReturn(Optional.of(5.0));
        when(reviewRepository.countByProductId(productId)).thenReturn(1);

        Review saved = reviewService.createReview(userId, request);

        assertThat(saved.getOrderItemId()).isEqualTo(orderItemId);
        verify(reviewRepository).save(any(Review.class));
        verify(productService).evictProductDetailCache(productId);
    }

    @Test
    @DisplayName("createReview - 평균 평점은 소수 둘째 자리 반올림으로 업데이트")
    void createReview_updatesProductRatingRoundedToTwoDecimals() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 5, "좋음", "내용");
        Product product = mock(Product.class);

        mockValidDeliveredOrderItem(orderItemId, userId, productId);
        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(false);
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
        verify(productService).evictProductDetailCache(productId);
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
        verifyNoInteractions(productService);
    }

    @Test
    @DisplayName("deleteReview - 삭제 성공 시 productDetail 캐시 evict 호출")
    void deleteReview_success_evictsProductDetailCache() {
        Long reviewId = 1L;
        Long userId = 2L;
        Long productId = 100L;
        Review review = new Review(productId, userId, null, 5, "삭제", "대상");

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        Product product = mock(Product.class);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findAverageRatingByProductId(productId)).thenReturn(Optional.of(0.0));
        when(reviewRepository.countByProductId(productId)).thenReturn(0);

        reviewService.deleteReview(reviewId, userId);

        verify(reviewRepository).delete(review);
        verify(productService).evictProductDetailCache(productId);
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

    @Test
    @DisplayName("createReview - 평균값이 없으면 0.00과 리뷰 수로 상품 평점 갱신")
    void createReview_whenNoAverage_updatesZeroRating() {
        Long userId = 11L;
        Long productId = 101L;
        Long orderItemId = 1001L;
        ReviewCreateRequest request = new ReviewCreateRequest(productId, orderItemId, 2, "첫 리뷰", "내용");
        Product product = mock(Product.class);

        mockValidDeliveredOrderItem(orderItemId, userId, productId);
        when(reviewRepository.existsByUserIdAndOrderItemId(userId, orderItemId)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findAverageRatingByProductId(productId)).thenReturn(Optional.empty());
        when(reviewRepository.countByProductId(productId)).thenReturn(0);

        reviewService.createReview(userId, request);

        ArgumentCaptor<BigDecimal> avgCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(product).updateRating(avgCaptor.capture(), eq(0));
        assertThat(avgCaptor.getValue())
                .as("리뷰 평균값이 없으면 기본 평점 0.00으로 갱신되어야 함")
                .isEqualByComparingTo("0.00");
        verify(productService).evictProductDetailCache(productId);
    }

    @Test
    @DisplayName("markHelpful - 본인 리뷰에는 도움이 돼요를 누를 수 없음")
    void markHelpful_selfVote_throwsBusinessException() {
        Long reviewId = 1L;
        Long userId = 2L;
        Review ownReview = new Review(100L, userId, null, 4, "t", "c");

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(ownReview));

        assertThatThrownBy(() -> reviewService.markHelpful(reviewId, userId))
                .as("리뷰 작성자 본인은 도움이 돼요를 누를 수 없어야 함")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 리뷰");

        verifyNoInteractions(reviewHelpfulRepository);
        verify(reviewRepository, never()).incrementHelpfulCount(any());
        verify(reviewRepository, never()).decrementHelpfulCount(any());
    }

    @Test
    @DisplayName("markHelpful - insert 충돌 시에도 true 반환하고 카운트는 증가하지 않음")
    void markHelpful_insertConflict_returnsTrueWithoutIncrement() {
        Long reviewId = 1L;
        Long userId = 2L;
        Review review = new Review(100L, 99L, null, 4, "t", "c");

        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewHelpfulRepository.deleteByReviewIdAndUserIdNative(reviewId, userId)).thenReturn(0);
        when(reviewHelpfulRepository.insertIgnoreConflict(reviewId, userId)).thenReturn(0);

        boolean result = reviewService.markHelpful(reviewId, userId);

        assertThat(result)
                .as("insert 충돌 시에는 기존 도움 여부 조회 결과를 반환해야 함")
                .isFalse();
        verify(reviewRepository, never()).incrementHelpfulCount(any());
        verify(reviewRepository, never()).decrementHelpfulCount(any());
        verify(reviewHelpfulRepository).existsByReviewIdAndUserId(reviewId, userId);
        verifyNoInteractions(productService);
    }


    @Test
    @DisplayName("ReviewCreateRequest - 제목 길이 200자 초과면 검증 실패")
    void reviewCreateRequest_titleTooLong_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String overTitle = "a".repeat(201);
        ReviewCreateRequest request = new ReviewCreateRequest(1L, 2L, 5, overTitle, "정상 내용");

        assertThat(validator.validate(request))
                .extracting("message")
                .contains("리뷰 제목은 200자 이하로 입력해주세요.");
    }

    @Test
    @DisplayName("ReviewCreateRequest - orderItemId가 null이면 검증 실패")
    void reviewCreateRequest_nullOrderItem_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ReviewCreateRequest request = new ReviewCreateRequest(1L, null, 5, "정상 제목", "정상 내용");

        assertThat(validator.validate(request))
                .extracting("message")
                .contains("주문 항목 정보가 누락되었습니다.");
    }

    @Test
    @DisplayName("ReviewCreateRequest - 내용 길이 5,000자 초과면 검증 실패")
    void reviewCreateRequest_contentTooLong_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String overContent = "a".repeat(5001);
        ReviewCreateRequest request = new ReviewCreateRequest(1L, 2L, 5, "정상 제목", overContent);

        assertThat(validator.validate(request))
                .extracting("message")
                .contains("리뷰 내용은 5,000자 이하로 입력해주세요.");
    }

    @Test
    @DisplayName("ReviewCreateRequest - 제목/내용 공백만 입력하면 검증 실패")
    void reviewCreateRequest_blankOnlyTitleAndContent_validationFails() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ReviewCreateRequest request = new ReviewCreateRequest(1L, 2L, 5, "   ", "\t\n");

        assertThat(validator.validate(request))
                .extracting("message")
                .contains("리뷰 제목은 공백만 입력할 수 없습니다.", "리뷰 내용은 공백만 입력할 수 없습니다.");
    }

}
