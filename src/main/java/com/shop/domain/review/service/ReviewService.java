package com.shop.domain.review.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.review.dto.ReviewCreateRequest;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.repository.ReviewRepository;
import com.shop.domain.review.repository.ReviewHelpfulRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewHelpfulRepository reviewHelpfulRepository;
    private final ProductRepository productRepository;

    public ReviewService(ReviewRepository reviewRepository,
                         ReviewHelpfulRepository reviewHelpfulRepository,
                         ProductRepository productRepository) {
        this.reviewRepository = reviewRepository;
        this.reviewHelpfulRepository = reviewHelpfulRepository;
        this.productRepository = productRepository;
    }

    @Cacheable(value = "productReviews", key = "#productId + ':' + #pageable.pageNumber")
    public Page<Review> getProductReviews(Long productId, Pageable pageable) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
    }

    @Transactional
    @CacheEvict(value = "productReviews", allEntries = true)
    public Review createReview(Long userId, ReviewCreateRequest request) {
        if (request.orderItemId() != null &&
            reviewRepository.existsByUserIdAndOrderItemId(userId, request.orderItemId())) {
            throw new BusinessException("DUPLICATE_REVIEW", "이미 리뷰를 작성하였습니다.");
        }

        Review review = new Review(request.productId(), userId, request.orderItemId(),
                request.rating(), request.title(), request.content());
        Review saved = reviewRepository.save(review);

        updateProductRating(request.productId());
        return saved;
    }

    @Transactional
    @CacheEvict(value = "productReviews", allEntries = true)
    public void deleteReview(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        if (!review.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN", "본인의 리뷰만 삭제할 수 있습니다.");
        }
        Long productId = review.getProductId();
        reviewRepository.delete(review);
        updateProductRating(productId);
    }

    private void updateProductRating(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Double avg = reviewRepository.findAverageRatingByProductId(productId).orElse(0.0);
        int count = reviewRepository.countByProductId(productId);
        product.updateRating(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP), count);
    }

    @Transactional
    @CacheEvict(value = "productReviews", allEntries = true)
    public boolean markHelpful(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("리뷰", reviewId));
        if (review.getUserId().equals(userId)) {
            throw new BusinessException("SELF_HELPFUL", "본인의 리뷰에는 도움이 돼요를 누를 수 없습니다.");
        }

        // 1단계: 삭제 시도 (이미 눌렀으면 취소)
        int deleted = reviewHelpfulRepository.deleteByReviewIdAndUserIdNative(reviewId, userId);
        if (deleted > 0) {
            reviewRepository.decrementHelpfulCount(reviewId);
            return false; // 취소됨
        }

        // 2단계: 삽입 시도 (ON CONFLICT DO NOTHING → UNIQUE 위반 시 예외 없이 0 반환)
        int inserted = reviewHelpfulRepository.insertIgnoreConflict(reviewId, userId);
        if (inserted > 0) {
            reviewRepository.incrementHelpfulCount(reviewId);
        }
        return true; // 추가됨 (또는 이미 존재)
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
