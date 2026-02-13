package com.shop.domain.review.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "images", columnDefinition = "jsonb")
    private String images;

    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Review() {}

    public Review(Long productId, Long userId, Long orderItemId, int rating, String title, String content) {
        this.productId = productId;
        this.userId = userId;
        this.orderItemId = orderItemId;
        this.rating = rating;
        this.title = title;
        this.content = content;
        this.helpfulCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void update(int rating, String title, String content) {
        this.rating = rating;
        this.title = title;
        this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementHelpful() { this.helpfulCount++; }

    public Long getReviewId() { return reviewId; }
    public Long getProductId() { return productId; }
    public Long getUserId() { return userId; }
    public Long getOrderItemId() { return orderItemId; }
    public Integer getRating() { return rating; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getImages() { return images; }
    public Integer getHelpfulCount() { return helpfulCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
