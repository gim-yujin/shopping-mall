package com.shop.domain.order.dto;

import com.shop.domain.order.entity.OrderItem;

import java.math.BigDecimal;

/**
 * [P1-6] 주문 항목 응답 DTO.
 */
public record OrderItemResponse(
        Long orderItemId,
        Long productId,
        String productName,
        int quantity,
        int cancelledQuantity,
        int returnedQuantity,
        int remainingQuantity,
        BigDecimal unitPrice,
        BigDecimal subtotal,
        BigDecimal cancelledAmount,
        BigDecimal returnedAmount
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getProductName(),
                item.getQuantity(),
                item.getCancelledQuantity(),
                item.getReturnedQuantity(),
                item.getRemainingQuantity(),
                item.getUnitPrice(),
                item.getSubtotal(),
                item.getCancelledAmount(),
                item.getReturnedAmount()
        );
    }
}
