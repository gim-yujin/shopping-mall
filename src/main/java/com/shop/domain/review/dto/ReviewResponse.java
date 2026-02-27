package com.shop.domain.review.dto;

import com.shop.domain.review.entity.Review;

import java.time.LocalDateTime;

/**
 * [P1-6] 리뷰 응답 DTO.
 */
public record ReviewResponse(
        Long reviewId,
        Long productId,
        Long userId,
        int rating,
        String title,
        String content,
        int helpfulCount,
        LocalDateTime createdAt
) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getReviewId(),
                review.getProductId(),
                review.getUserId(),
                review.getRating(),
                review.getTitle(),
                review.getContent(),
                review.getHelpfulCount() != null ? review.getHelpfulCount() : 0,
                review.getCreatedAt()
        );
    }
}
