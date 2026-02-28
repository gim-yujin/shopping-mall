package com.shop.domain.order.repository;

import com.shop.domain.order.entity.OrderItem;
import jakarta.persistence.LockModeType;
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
}
