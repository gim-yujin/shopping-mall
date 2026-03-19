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

    /**
     * [Phase 18] OrderListReadModel(CQRS 읽기 모델)에서 변환.
     *
     * <p>기존 from(Order)은 {@code order.getItems().size()}로 아이템 수를 계산하여
     * Lazy 컬렉션 초기화가 필요했다. 읽기 모델은 item_count를 서브쿼리로 미리 가져오므로
     * 추가 쿼리 없이 바로 변환 가능하다.</p>
     */
    public static OrderSummaryResponse from(OrderListReadModel readModel) {
        return new OrderSummaryResponse(
                readModel.orderId(),
                readModel.orderNumber(),
                readModel.orderStatus(),
                readModel.totalAmount(),
                readModel.discountAmount(),
                readModel.shippingFee(),
                readModel.finalAmount(),
                readModel.itemCount(),
                readModel.orderDate()
        );
    }
}
