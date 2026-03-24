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
        String username,
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
                null,
                review.getRating(),
                review.getTitle(),
                review.getContent(),
                review.getHelpfulCount() != null ? review.getHelpfulCount() : 0,
                review.getCreatedAt()
        );
    }

    /**
     * [Phase 22] CQRS 읽기 모델에서 응답 DTO 변환.
     * username을 포함하여 별도 User 조회 없이 작성자명을 표시한다.
     */
    public static ReviewResponse from(ReviewListReadModel readModel) {
        return new ReviewResponse(
                readModel.reviewId(),
                readModel.productId(),
                readModel.userId(),
                readModel.username(),
                readModel.rating() != null ? readModel.rating() : 0,
                readModel.title(),
                readModel.content(),
                readModel.helpfulCount() != null ? readModel.helpfulCount() : 0,
                readModel.createdAt()
        );
    }
}
