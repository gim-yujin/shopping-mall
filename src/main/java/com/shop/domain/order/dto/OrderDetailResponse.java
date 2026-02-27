package com.shop.domain.order.dto;

import com.shop.domain.order.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [P1-6] 주문 상세 조회용 응답 DTO.
 * 주문 항목(items)을 포함하여 단일 API 호출로 전체 주문 정보를 제공한다.
 */
public record OrderDetailResponse(
        Long orderId,
        String orderNumber,
        String orderStatus,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal tierDiscountAmount,
        BigDecimal couponDiscountAmount,
        BigDecimal shippingFee,
        BigDecimal finalAmount,
        int earnedPoints,
        int usedPoints,
        String paymentMethod,
        String shippingAddress,
        String recipientName,
        String recipientPhone,
        List<OrderItemResponse> items,
        LocalDateTime orderDate,
        LocalDateTime paidAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt
) {
    public static OrderDetailResponse from(Order order) {
        return new OrderDetailResponse(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getOrderStatusCode(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getTierDiscountAmount(),
                order.getCouponDiscountAmount(),
                order.getShippingFee(),
                order.getFinalAmount(),
                order.getEarnedPointsSnapshot(),
                order.getUsedPoints(),
                order.getPaymentMethod(),
                order.getShippingAddress(),
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getOrderDate(),
                order.getPaidAt(),
                order.getShippedAt(),
                order.getDeliveredAt(),
                order.getCancelledAt()
        );
    }
}
