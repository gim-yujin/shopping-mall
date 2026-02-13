package com.shop.domain.review.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "review_helpfuls",
       uniqueConstraints = @UniqueConstraint(columnNames = {"review_id", "user_id"}))
public class ReviewHelpful {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "helpful_id")
    private Long helpfulId;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ReviewHelpful() {}

    public ReviewHelpful(Long reviewId, Long userId) {
        this.reviewId = reviewId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getHelpfulId() { return helpfulId; }
    public Long getReviewId() { return reviewId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
