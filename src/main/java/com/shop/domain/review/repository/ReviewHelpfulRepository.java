package com.shop.domain.review.repository;

import com.shop.domain.review.entity.ReviewHelpful;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface ReviewHelpfulRepository extends JpaRepository<ReviewHelpful, Long> {

    boolean existsByReviewIdAndUserId(Long reviewId, Long userId);

    @Query("SELECT rh.reviewId FROM ReviewHelpful rh WHERE rh.userId = :userId AND rh.reviewId IN :reviewIds")
    Set<Long> findHelpedReviewIdsByUserIdAndReviewIds(@Param("userId") Long userId,
                                                      @Param("reviewIds") Set<Long> reviewIds);

    // 네이티브 DELETE — 영향받은 행 수 반환 (0 또는 1)
    @Modifying
    @Query(value = "DELETE FROM review_helpfuls WHERE review_id = :reviewId AND user_id = :userId",
           nativeQuery = true)
    int deleteByReviewIdAndUserIdNative(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    // 네이티브 INSERT ON CONFLICT DO NOTHING — 영향받은 행 수 반환 (0 또는 1)
    @Modifying
    @Query(value = "INSERT INTO review_helpfuls (review_id, user_id, created_at) " +
                   "VALUES (:reviewId, :userId, NOW()) " +
                   "ON CONFLICT (review_id, user_id) DO NOTHING",
           nativeQuery = true)
    int insertIgnoreConflict(@Param("reviewId") Long reviewId, @Param("userId") Long userId);
}
