package com.shop.domain.review.repository;

import com.shop.domain.review.entity.ReviewHelpful;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;

public interface ReviewHelpfulRepository extends JpaRepository<ReviewHelpful, Long> {

    Optional<ReviewHelpful> findByReviewIdAndUserId(Long reviewId, Long userId);

    boolean existsByReviewIdAndUserId(Long reviewId, Long userId);

    @Query("SELECT rh.reviewId FROM ReviewHelpful rh WHERE rh.userId = :userId AND rh.reviewId IN :reviewIds")
    Set<Long> findHelpedReviewIdsByUserIdAndReviewIds(@Param("userId") Long userId,
                                                      @Param("reviewIds") Set<Long> reviewIds);
}
