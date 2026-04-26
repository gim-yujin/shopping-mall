package com.shop.domain.point.repository;

import com.shop.domain.point.entity.PointHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /**
     * 사용자의 포인트 변동 이력을 최신순으로 조회한다.
     * 마이페이지 포인트 이력 화면에서 사용.
     */
    @Query("SELECT ph FROM PointHistory ph WHERE ph.userId = :userId ORDER BY ph.createdAt DESC")
    Page<PointHistory> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId, Pageable pageable);

    /**
     * 운영용 포인트 이력 조회.
     * 기간/유형 필터를 선택적으로 적용하고 최신순으로 반환한다.
     */
    @Query("""
            SELECT ph
            FROM PointHistory ph
            WHERE (:fromDateTime IS NULL OR ph.createdAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR ph.createdAt < :toDateTime)
              AND (:changeType IS NULL OR ph.changeType = :changeType)
            ORDER BY ph.createdAt DESC
            """)
    Page<PointHistory> findForOps(@Param("fromDateTime") LocalDateTime fromDateTime,
                                  @Param("toDateTime") LocalDateTime toDateTime,
                                  @Param("changeType") String changeType,
                                  Pageable pageable);

    /**
     * 특정 주문에 연관된 포인트 이력을 조회한다.
     * 주문 상세/CS 문의 대응 시 사용.
     *
     * <p>참조 유형 종류:
     * <ul>
     *   <li>ORDER — 주문 시 USE/EARN</li>
     *   <li>CANCEL — 전체 취소 시 REFUND ({@code OrderCancellationService})</li>
     *   <li>PARTIAL_CANCEL — 부분 취소 시 REFUND ({@code PartialCancellationService})</li>
     *   <li>RETURN — 반품 승인 시 REFUND ({@code PartialCancellationService})</li>
     * </ul>
     * 부분 취소/반품 시 발생하는 REFUND 행이 누락되지 않도록 IN 절에 모두 포함한다.
     */
    @Query("""
            SELECT ph FROM PointHistory ph
             WHERE ph.referenceId = :orderId
               AND ph.referenceType IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN')
             ORDER BY ph.createdAt ASC
            """)
    java.util.List<PointHistory> findByOrderId(@Param("orderId") Long orderId);

    /**
     * 운영 정합성 점검: 특정 주문의 REFUND 합계.
     * {@code orders.refunded_points}와 일치해야 한다.
     */
    @Query("""
            SELECT COALESCE(SUM(ph.amount), 0)
              FROM PointHistory ph
             WHERE ph.referenceId = :orderId
               AND ph.changeType = 'REFUND'
               AND ph.referenceType IN ('CANCEL', 'PARTIAL_CANCEL', 'RETURN')
            """)
    long sumRefundedPointsByOrderId(@Param("orderId") Long orderId);

    /**
     * 운영 정합성 점검: 특정 주문의 USE 합계.
     * {@code orders.used_points}와 일치해야 한다 (사용 포인트는 주문 1건당 1회만 기록).
     */
    @Query("""
            SELECT COALESCE(SUM(ph.amount), 0)
              FROM PointHistory ph
             WHERE ph.referenceId = :orderId
               AND ph.changeType = 'USE'
               AND ph.referenceType = 'ORDER'
            """)
    long sumUsedPointsByOrderId(@Param("orderId") Long orderId);
}
