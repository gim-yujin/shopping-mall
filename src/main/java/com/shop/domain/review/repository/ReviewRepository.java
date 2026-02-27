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

    /**
     * [BUG FIX] 전체 리뷰의 helpfulCount를 실제 review_helpfuls 레코드 수로 일괄 동기화한다.
     *
     * 동시 토글 시 increment/decrement 사이에 다른 스레드가 개입하면
     * helpful_count가 실제 레코드 수와 미세하게 불일치할 수 있다.
     * 이 메서드는 review_helpfuls를 GROUP BY한 실제 카운트로 한 번에 보정한다.
     *
     * 두 가지 불일치 유형을 처리한다:
     *   1) helpful_count < 실제 레코드 수 (increment 누락)
     *   2) helpful_count > 실제 레코드 수 (decrement 누락)
     *
     * ReviewHelpfulSyncScheduler에서 매일 새벽에 호출된다.
     *
     * @return 보정된 리뷰 수
     */
    @Modifying
    @Query(value = "UPDATE reviews r " +
            "SET helpful_count = sub.actual_count " +
            "FROM (" +
            "  SELECT review_id, COUNT(*) as actual_count " +
            "  FROM review_helpfuls " +
            "  GROUP BY review_id" +
            ") sub " +
            "WHERE r.review_id = sub.review_id AND r.helpful_count != sub.actual_count",
            nativeQuery = true)
    int syncAllHelpfulCounts();

    /**
     * [BUG FIX] review_helpfuls 레코드가 0건인데 helpful_count > 0인 리뷰를 보정한다.
     *
     * syncAllHelpfulCounts()는 review_helpfuls에 레코드가 존재하는 리뷰만 보정한다.
     * 모든 "도움이 돼요"가 취소되어 review_helpfuls에 레코드가 없지만
     * helpful_count가 양수로 남아있는 경우(decrement 누락 또는 삭제 후 미반영)는
     * 이 쿼리로 별도 처리한다.
     *
     * @return 보정된 리뷰 수
     */
    @Modifying
    @Query(value = "UPDATE reviews " +
            "SET helpful_count = 0 " +
            "WHERE helpful_count > 0 " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM review_helpfuls rh WHERE rh.review_id = reviews.review_id" +
            ")",
            nativeQuery = true)
    int resetOrphanedHelpfulCounts();
}
