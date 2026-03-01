package com.shop.domain.order.repository;

import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrder_OrderId(Long orderId);

    @Query("""
            SELECT oi FROM OrderItem oi
            JOIN FETCH oi.order o
            WHERE o.userId = :userId
              AND oi.productId = :productId
              AND o.orderStatus = com.shop.domain.order.entity.OrderStatus.DELIVERED
              AND NOT EXISTS (
                  SELECT 1 FROM Review r
                  WHERE r.userId = :userId
                    AND r.orderItemId = oi.orderItemId
              )
            ORDER BY o.deliveredAt DESC, oi.orderItemId DESC
            """)
    List<OrderItem> findDeliveredItemsForReviewExcludingReviewed(@Param("userId") Long userId, @Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT oi FROM OrderItem oi
            JOIN FETCH oi.order o
            WHERE oi.orderItemId = :orderItemId
              AND o.orderId = :orderId
              AND o.userId = :userId
            """)
    Optional<OrderItem> findByIdAndOrderIdAndUserIdWithLock(@Param("orderItemId") Long orderItemId,
                                                             @Param("orderId") Long orderId,
                                                             @Param("userId") Long userId);

    // ── Step 3 신규: 반품 관리 쿼리 ──────────────────────────────

    /**
     * 반품 신청 대기 목록 조회 (관리자용).
     *
     * <p><b>설계 의도:</b> RETURN_REQUESTED 상태의 아이템을 신청일 순으로 페이징 조회한다.
     * Order를 JOIN FETCH하여 N+1 문제를 방지하고, 관리자 페이지에서 주문번호·사용자 정보를
     * 바로 표시할 수 있도록 한다.</p>
     *
     * <p><b>인덱스 활용:</b> V8 마이그레이션에서 생성한 partial index
     * {@code idx_order_items_status_return_requested}를 활용하여
     * 전체 order_items 스캔 없이 반품 신청 건만 빠르게 조회한다.</p>
     *
     * <p><b>countQuery 분리:</b> Page 쿼리에 JOIN FETCH가 포함되면
     * Hibernate가 count 쿼리에도 FETCH를 적용하여 오류가 발생할 수 있다.
     * 별도 countQuery를 지정하여 이 문제를 방지한다.</p>
     */
    @Query(value = """
            SELECT oi FROM OrderItem oi
            JOIN FETCH oi.order o
            WHERE oi.status = :status
            ORDER BY oi.returnRequestedAt ASC
            """,
            countQuery = """
            SELECT COUNT(oi) FROM OrderItem oi
            WHERE oi.status = :status
            """)
    Page<OrderItem> findByStatus(@Param("status") OrderItemStatus status, Pageable pageable);

    /**
     * 특정 상태의 아이템 수를 카운트한다 (대시보드 통계용).
     *
     * <p>관리자 대시보드에서 반품 대기 건수 카드를 표시하기 위해 사용한다.
     * RETURN_REQUESTED 상태에 대해 호출하면 현재 처리 대기 중인 반품 건수를 반환한다.</p>
     *
     * <p>partial index가 WHERE status = 'RETURN_REQUESTED'에 대해 적용되므로
     * 전체 테이블 스캔 없이 빠르게 카운트할 수 있다.</p>
     */
    long countByStatus(OrderItemStatus status);
}
