package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderItemRepository;
import com.shop.domain.review.dto.ReviewCreateRequest;
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
        return orderItemRepository.findDeliveredItemsForReview(userId, productId).stream()
                .filter(orderItem -> !reviewRepository.existsByUserIdAndOrderItemId(userId, orderItem.getOrderItemId()))
                .toList();
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
        boolean duplicated = request.orderItemId() == null
                ? reviewRepository.existsByUserIdAndProductId(userId, request.productId())
                : reviewRepository.existsByUserIdAndOrderItemId(userId, request.orderItemId());

        if (duplicated) {
            throw new BusinessException("DUPLICATE_REVIEW", "이미 리뷰를 작성하였습니다.");
        }
    }

    private void validateOrderItemForReview(Long userId, ReviewCreateRequest request) {
        if (request.orderItemId() == null) {
            return;
        }

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

    private void updateProductRating(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Double avg = reviewRepository.findAverageRatingByProductId(productId).orElse(0.0);
        int count = reviewRepository.countByProductId(productId);
        product.updateRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP), count);
    }

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
