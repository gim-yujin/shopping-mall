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

    boolean existsByUserIdAndProductIdAndOrderItemIdIsNull(Long userId, Long productId);

    Page<Review> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE Review r SET r.helpfulCount = r.helpfulCount + 1 WHERE r.reviewId = :reviewId")
    void incrementHelpfulCount(@Param("reviewId") Long reviewId);

    @Modifying
    @Query("UPDATE Review r SET r.helpfulCount = r.helpfulCount - 1 WHERE r.reviewId = :reviewId AND r.helpfulCount > 0")
    void decrementHelpfulCount(@Param("reviewId") Long reviewId);

    /**
     * [BUG FIX] helpfulCount를 실제 review_helpfuls 레코드 수로 동기화한다.
     * 실시간 경로에서는 increment/decrement가 더 빠르고 동시성에 안전하지만,
     * 동시 토글 시 DELETE→decrement 사이에 다른 스레드가 개입하면
     * count가 실제 레코드 수와 미세하게 불일치할 수 있다.
     * 이 메서드를 주기적으로(예: 야간 배치) 호출하면 누적된 오차를 보정할 수 있다.
     */
    @Modifying
    @Query(value = "UPDATE reviews SET helpful_count = " +
            "(SELECT COUNT(*) FROM review_helpfuls rh WHERE rh.review_id = :reviewId) " +
            "WHERE review_id = :reviewId", nativeQuery = true)
    void syncHelpfulCount(@Param("reviewId") Long reviewId);
}
