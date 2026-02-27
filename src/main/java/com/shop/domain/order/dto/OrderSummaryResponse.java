package com.shop.domain.order.dto;

import com.shop.domain.order.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [P1-6] 주문 목록 조회용 응답 DTO.
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        String orderStatus,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal shippingFee,
        BigDecimal finalAmount,
        int itemCount,
        LocalDateTime orderDate
) {
    public static OrderSummaryResponse from(Order order) {
        return new OrderSummaryResponse(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getOrderStatusCode(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getShippingFee(),
                order.getFinalAmount(),
                order.getItems().size(),
                order.getOrderDate()
        );
    }
}
