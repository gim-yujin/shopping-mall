package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.global.event.ReviewRatingChangedEvent;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * [Phase 25] 리뷰 변경 후처리 — 상품 평점 재계산 + 캐시 무효화.
 *
 * <p><b>문제:</b> ReviewService에서 리뷰 저장과 평점 재계산이 동일 트랜잭션에서 실행되어:
 * <ul>
 *   <li>메인 TX가 rating 쿼리(2회) + update(1회)만큼 길어짐</li>
 *   <li>캐시 evict가 커밋 전에 발생하여 동시 읽기 시 stale 캐시 경합 가능</li>
 * </ul></p>
 *
 * <p><b>해결:</b> {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)}로 후처리를 분리한다.
 * <ul>
 *   <li>AFTER_COMMIT: 리뷰 저장 커밋 후에만 실행 → 캐시 무효화 타이밍 정확</li>
 *   <li>REQUIRES_NEW: 원본 TX는 이미 커밋되었으므로, 별도 TX에서 평점 업데이트</li>
 *   <li>동기 실행: 평점 갱신이 빠르고(2쿼리+1업데이트), 즉시 반영되어야 하므로 @Async 불필요</li>
 * </ul></p>
 *
 * @see com.shop.domain.order.service.OrderPostProcessingListener 유사 패턴 (주문 후처리)
 */
@Component
public class ReviewPostProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewPostProcessingListener.class);
    private static final String PRODUCT_REVIEW_VERSION_CACHE = "productReviewVersion";

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final ProductService productService;
    private final CacheManager cacheManager;

    public ReviewPostProcessingListener(ProductRepository productRepository,
                                         ReviewRepository reviewRepository,
                                         ProductService productService,
                                         CacheManager cacheManager) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.productService = productService;
        this.cacheManager = cacheManager;
    }

    /**
     * 리뷰 변경 이벤트 처리 — 상품 평점 재계산 + 캐시 무효화 + 리뷰 버전 범프.
     *
     * <p>createReview, updateReview, deleteReview 모두 동일한 후처리가 필요하므로
     * 단일 이벤트 핸들러로 통합한다.</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleReviewRatingChanged(ReviewRatingChangedEvent event) {
        Long productId = event.productId();
        try {
            updateProductRating(productId);
            productService.evictProductDetailCache(productId);
            bumpProductReviewVersion(productId);
        } catch (Exception e) {
            log.error("리뷰 후처리 실패 — productId={}: {}", productId, e.getMessage(), e);
        }
    }

    void updateProductRating(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Object[] stats = reviewRepository.findRatingStatsByProductId(productId).get(0);
        Double avg = stats[0] != null ? ((Number) stats[0]).doubleValue() : 0.0;
        int count = ((Number) stats[1]).intValue();
        product.updateRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP), count);
    }

    void bumpProductReviewVersion(Long productId) {
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
}
