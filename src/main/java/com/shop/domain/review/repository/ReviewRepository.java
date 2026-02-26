package com.shop.domain.review.repository;

import com.shop.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.productId = :productId")
    Optional<Double> findAverageRatingByProductId(@Param("productId") Long productId);

    int countByProductId(Long productId);

    boolean existsByUserIdAndOrderItemId(Long userId, Long orderItemId);

    boolean existsByUserIdAndProductId(Long userId, Long productId);

    Page<Review> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE Review r SET r.helpfulCount = r.helpfulCount + 1 WHERE r.reviewId = :reviewId")
    void incrementHelpfulCount(@Param("reviewId") Long reviewId);

    @Modifying
    @Query("UPDATE Review r SET r.helpfulCount = r.helpfulCount - 1 WHERE r.reviewId = :reviewId AND r.helpfulCount > 0")
    void decrementHelpfulCount(@Param("reviewId") Long reviewId);
}
