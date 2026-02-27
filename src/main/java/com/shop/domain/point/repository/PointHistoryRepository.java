package com.shop.domain.point.repository;

import com.shop.domain.point.entity.PointHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /**
     * 사용자의 포인트 변동 이력을 최신순으로 조회한다.
     * 마이페이지 포인트 이력 화면에서 사용.
     */
    @Query("SELECT ph FROM PointHistory ph WHERE ph.userId = :userId ORDER BY ph.createdAt DESC")
    Page<PointHistory> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, Pageable pageable);

    /**
     * 특정 주문에 연관된 포인트 이력을 조회한다.
     * 주문 상세/CS 문의 대응 시 사용.
     */
    @Query("SELECT ph FROM PointHistory ph WHERE ph.referenceId = :orderId AND ph.referenceType IN ('ORDER', 'CANCEL') ORDER BY ph.createdAt ASC")
    java.util.List<PointHistory> findByOrderId(@Param("orderId") Long orderId);
}
