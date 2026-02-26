package com.shop.domain.order.repository;

import com.shop.domain.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrder_OrderId(Long orderId);

    @Query("""
            SELECT oi FROM OrderItem oi
            JOIN FETCH oi.order o
            WHERE o.userId = :userId
              AND oi.productId = :productId
              AND o.orderStatus = com.shop.domain.order.entity.OrderStatus.DELIVERED
            ORDER BY o.deliveredAt DESC, oi.orderItemId DESC
            """)
    List<OrderItem> findDeliveredItemsForReview(@Param("userId") Long userId, @Param("productId") Long productId);
}
