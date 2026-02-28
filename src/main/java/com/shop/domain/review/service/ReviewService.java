package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.dto.ReviewUpdateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.domain.review.repository.ReviewHelpfulRepository;
import com.shop.global.cache.CacheKeyGenerator;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ReviewService {

    private static final String PRODUCT_REVIEW_CACHE = "productReviews";
    private static final String PRODUCT_REVIEW_VERSION_CACHE = "productReviewVersion";

    private final ReviewRepository reviewRepository;
    private final ReviewHelpfulRepository reviewHelpfulRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final OrderItemRepository orderItemRepository;
    private final CacheManager cacheManager;

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewHelpfulRepository reviewHelpfulRepository,
                         ProductRepository productRepository,
                         ProductService productService,
                         OrderItemRepository orderItemRepository,
                         CacheManager cacheManager) {
        this.reviewRepository = reviewRepository;
        this.reviewHelpfulRepository = reviewHelpfulRepository;
        this.productRepository = productRepository;
        this.productService = productService;
        this.orderItemRepository = orderItemRepository;
        this.cacheManager = cacheManager;
    }

    @Cacheable(value = PRODUCT_REVIEW_CACHE, key = "#root.target.productReviewCacheKey(#productId, #pageable)")
    public Page<Review> getProductReviews(Long productId, Pageable pageable) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
    }

    public String productReviewCacheKey(Long productId, Pageable pageable) {
        long version = getProductReviewVersion(productId);
        return CacheKeyGenerator.pageableWithPrefix(productId + ":v" + version, pageable);
    }

    public List<OrderItem> getReviewableOrderItems(Long userId, Long productId) {
        return orderItemRepository.findDeliveredItemsForReviewExcludingReviewed(userId, productId);
    }

    @Transactional
    public Review createReview(Long userId, ReviewCreateRequest request) {
        validateOrderItemForReview(userId, request);

        validateDuplicateReview(userId, request);

        Review review = new Review(request.productId(), userId, request.orderItemId(),
                request.rating(), request.title(), request.content());

        Review saved;
        try {
            saved = reviewRepository.save(review);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("DUPLICATE_REVIEW", "이미 리뷰를 작성하였습니다.");
        }

        updateProductRating(request.productId());
        productService.evictProductDetailCache(request.productId());
        bumpProductReviewVersion(request.productId());
        return saved;
    }

    private void validateDuplicateReview(Long userId, ReviewCreateRequest request) {
        boolean duplicated = reviewRepository.existsByUserIdAndOrderItemId(userId, request.orderItemId());

        if (duplicated) {
            throw new BusinessException("DUPLICATE_REVIEW", "이미 리뷰를 작성하였습니다.");
        }
    }

    private void validateOrderItemForReview(Long userId, ReviewCreateRequest request) {
        OrderItem orderItem = orderItemRepository.findById(request.orderItemId())
                .orElseThrow(() -> new BusinessException(
                        "REVIEW_ORDER_ITEM_NOT_FOUND",
                        "리뷰 대상 주문 항목을 찾을 수 없습니다."
                ));

        if (!orderItem.getOrder().getUserId().equals(userId)) {
            throw new BusinessException(
                    "REVIEW_ORDER_ITEM_FORBIDDEN",
                    "본인 주문의 상품에 대해서만 리뷰를 작성할 수 있습니다."
            );
        }

        if (!orderItem.getProductId().equals(request.productId())) {
            throw new BusinessException(
                    "REVIEW_PRODUCT_MISMATCH",
                    "주문 상품과 리뷰 상품이 일치하지 않습니다."
            );
        }

        if (orderItem.getOrder().getOrderStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(
                    "REVIEW_ORDER_STATUS_NOT_ALLOWED",
                    "배송 완료된 주문만 리뷰를 작성할 수 있습니다."
            );
        }
    }

    @Transactional
    public void deleteReview(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN", "본인의 리뷰만 삭제할 수 있습니다.");
        }
        Long productId = review.getProductId();
        reviewRepository.delete(review);
        updateProductRating(productId);
        productService.evictProductDetailCache(productId);
        bumpProductReviewVersion(productId);
    }

    /**
     * [3.7] 리뷰 수정 + 상품 평점 재계산.
     *
     * 리뷰 수정 시 rating이 변경될 수 있으므로 상품의 평균 평점을
     * 재계산하고, 관련 캐시(상품 상세, 리뷰 목록)를 무효화한다.
     * 본인 리뷰만 수정할 수 있도록 userId 검증을 수행한다.
     */
    @Transactional
    public Review updateReview(Long reviewId, Long userId, ReviewUpdateRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN", "본인의 리뷰만 수정할 수 있습니다.");
        }

        // Review.update()로 rating, title, content 변경 + updatedAt 갱신
        review.update(request.rating(), request.title(), request.content());

        // 평점 변경 가능성이 있으므로 상품 평균 평점 재계산
        Long productId = review.getProductId();
        updateProductRating(productId);
        productService.evictProductDetailCache(productId);
        bumpProductReviewVersion(productId);

        return review;
    }

    /**
     * [3.7] 리뷰 수정 폼 표시를 위한 단건 조회.
     * 본인 리뷰인지 검증한다.
     */
    public Review getReviewForEdit(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN", "본인의 리뷰만 수정할 수 있습니다.");
        }
        return review;
    }

    private void updateProductRating(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Double avg = reviewRepository.findAverageRatingByProductId(productId).orElse(0.0);
        int count = reviewRepository.countByProductId(productId);
        product.updateRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP), count);
    }

    /**
     * 리뷰 "도움이 돼요" 토글 (좋아요/취소).
     *
     * [P2-8] 동시성 경합(Race Condition) 설계 결정 문서.
     *
     * ── 동작 흐름 ──
     * 1단계: DELETE 시도 (이미 눌렀으면 취소)
     *   → deleted > 0 → decrementHelpfulCount → return false (취소됨)
     * 2단계: INSERT 시도 (ON CONFLICT DO NOTHING)
     *   → inserted > 0 → incrementHelpfulCount → return true (등록됨)
     *
     * ── 경합 시나리오 ──
     * 두 스레드 T1, T2가 동일 사용자·리뷰에 대해 동시에 markHelpful을 호출하는 경우:
     *
     *   T1: DELETE → deleted=0 (아직 없음)
     *   T2: DELETE → deleted=0 (아직 없음)
     *   T1: INSERT → inserted=1 (성공)
     *   T2: INSERT → inserted=0 (ON CONFLICT DO NOTHING, UNIQUE 제약에 의해 무시)
     *   T1: incrementHelpfulCount → helpfulCount +1 ✅
     *   T2: (inserted=0이므로 increment 안 함) ✅
     *
     * 이 경우 정상 동작한다. 그러나 다음 시나리오에서 count 불일치가 발생할 수 있다:
     *
     *   T1: DELETE → deleted=1 (기존 레코드 삭제)
     *   T2: DELETE → deleted=0 (T1이 이미 삭제)
     *   T1: decrementHelpfulCount → helpfulCount -1
     *   T2: INSERT → inserted=1 (새로 삽입)
     *   T2: incrementHelpfulCount → helpfulCount +1
     *
     * 결과: 실제로는 레코드 1개가 존재하지만, -1 +1 = ±0으로 count가 변동 없음.
     * 원래는 "삭제 후 재등록"이므로 count ±0이 맞을 수 있지만, 의도한 동작은
     * "T1이 취소, T2가 등록"이므로 count 변동이 사용자 기대와 다를 수 있다.
     *
     * ── 보정 전략 ──
     * {@link com.shop.domain.review.scheduler.ReviewHelpfulSyncScheduler}가 매일 새벽에
     * review_helpfuls 테이블의 실제 행 수와 reviews.helpful_count를 동기화한다.
     * 따라서 경합으로 인한 count 불일치는 최대 24시간 내에 자동 보정된다.
     *
     * ── 비관적 잠금을 선택하지 않은 이유 ──
     * "도움이 돼요"는 좋아요 성격의 기능으로, 정확한 실시간 count보다
     * 응답 속도와 동시성이 더 중요하다. 비관적 잠금은 hot review에 대한
     * 모든 helpful 요청을 직렬화하여 응답 지연을 유발한다.
     * count 오차는 비즈니스 영향이 낮고(주문 금액이나 재고가 아님),
     * 야간 동기화로 24시간 내 보정되므로 eventual consistency를 수용한다.
     */
    @Transactional
    public boolean markHelpful(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        Long productId = review.getProductId();
        if (review.getUserId().equals(userId)) {
            throw new BusinessException("SELF_HELPFUL", "본인의 리뷰에는 도움이 돼요를 누를 수 없습니다.");
        }

        // 1단계: 삭제 시도 (이미 눌렀으면 취소)
        int deleted = reviewHelpfulRepository.deleteByReviewIdAndUserIdNative(reviewId, userId);
        if (deleted > 0) {
            reviewRepository.decrementHelpfulCount(reviewId);
            bumpProductReviewVersion(productId);
            return false; // 취소됨
        }

        // 2단계: 삽입 시도 (ON CONFLICT DO NOTHING → UNIQUE 위반 시 예외 없이 0 반환)
        int inserted = reviewHelpfulRepository.insertIgnoreConflict(reviewId, userId);
        if (inserted > 0) {
            reviewRepository.incrementHelpfulCount(reviewId);
            bumpProductReviewVersion(productId);
            return true;
        }

        return reviewHelpfulRepository.existsByReviewIdAndUserId(reviewId, userId);
    }

    private long getProductReviewVersion(Long productId) {
        Cache cache = cacheManager.getCache(PRODUCT_REVIEW_VERSION_CACHE);
        if (cache == null) {
            return 0L;
        }
        Long version = cache.get(productId, Long.class);
        return version == null ? 0L : version;
    }

    private void bumpProductReviewVersion(Long productId) {
        Cache cache = cacheManager.getCache(PRODUCT_REVIEW_VERSION_CACHE);
        if (cache == null) {
            return;
        }

        if (cache instanceof CaffeineCache caffeineCache) {
            caffeineCache.getNativeCache().asMap().merge(productId, 1L, (a, b) -> ((Long) a) + ((Long) b));
            return;
        }

        synchronized (this) {
            Long current = cache.get(productId, Long.class);
            cache.put(productId, Objects.requireNonNullElse(current, 0L) + 1L);
        }
    }

    public Set<Long> getHelpedReviewIds(Long userId, Set<Long> reviewIds) {
        if (userId == null || reviewIds.isEmpty()) {
            return Set.of();
        }
        return reviewHelpfulRepository.findHelpedReviewIdsByUserIdAndReviewIds(userId, reviewIds);
    }

    public Page<Review> getUserReviews(Long userId, Pageable pageable) {
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }
}
