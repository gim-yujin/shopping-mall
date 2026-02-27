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
        BigDecimal unitPrice,
        BigDecimal subtotal
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getProductName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSubtotal()
        );
    }
}
