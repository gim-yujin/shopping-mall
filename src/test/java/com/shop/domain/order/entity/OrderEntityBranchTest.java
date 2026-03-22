package com.shop.domain.order.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order/OrderItem 엔티티 분기 커버리지 보강 테스트.
 *
 * <p>기존 OrderEntityUnitTest에서 다루지 않은 getter 분기를 검증한다:
 * - Order: markShipped(carrier, trackingNumber), getTrackingNumber, getCarrier 등
 * - Order: addRefundedAmount, addRefundedPoints, getOrderStatusCode
 * - OrderItem: getUnitPrice, getDiscountRate, getCancelledAmount, getCreatedAt</p>
 */
class OrderEntityBranchTest {

    private Order createOrder() {
        return new Order("ORD-TEST", 1L,
                BigDecimal.valueOf(50000), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(3000), BigDecimal.valueOf(53000),
                BigDecimal.valueOf(1.50), 795, 0,
                "CARD", "서울시 강남구", "홍길동", "010-1234-5678");
    }

    // ── Order: markShipped with carrier/trackingNumber ──

    @Test
    @DisplayName("markShipped(carrier, trackingNumber) — 배송 정보와 함께 SHIPPED 전이")
    void markShipped_withCarrierAndTracking() {
        Order order = createOrder();
        order.markPaid();

        order.markShipped("CJ대한통운", "1234567890");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getCarrier()).isEqualTo("CJ대한통운");
        assertThat(order.getTrackingNumber()).isEqualTo("1234567890");
        assertThat(order.getShippedAt()).isNotNull();
    }

    // ── Order: addRefundedAmount / addRefundedPoints ──

    @Test
    @DisplayName("addRefundedAmount — 환불 금액 누적")
    void addRefundedAmount_accumulates() {
        Order order = createOrder();
        assertThat(order.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        order.addRefundedAmount(BigDecimal.valueOf(10000));
        order.addRefundedAmount(BigDecimal.valueOf(5000));

        assertThat(order.getRefundedAmount()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("addRefundedPoints — 환불 포인트 누적")
    void addRefundedPoints_accumulates() {
        Order order = createOrder();
        assertThat(order.getRefundedPoints()).isEqualTo(0);

        order.addRefundedPoints(100);
        order.addRefundedPoints(50);

        assertThat(order.getRefundedPoints()).isEqualTo(150);
    }

    // ── Order: getOrderStatusCode ──

    @Test
    @DisplayName("getOrderStatusCode — 상태 enum name 반환")
    void getOrderStatusCode_returnsEnumName() {
        Order order = createOrder();
        assertThat(order.getOrderStatusCode()).isEqualTo("PENDING");
    }

    // ── Order: 기타 getter 검증 ──

    @Test
    @DisplayName("주문 생성 시 기본값 검증")
    void orderDefaults_verified() {
        Order order = createOrder();

        // 기본 필드 검증
        assertThat(order.getOrderNumber()).isEqualTo("ORD-TEST");
        assertThat(order.getUserId()).isEqualTo(1L);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("50000");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getTierDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getCouponDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getShippingFee()).isEqualByComparingTo("3000");
        assertThat(order.getFinalAmount()).isEqualByComparingTo("53000");
        assertThat(order.getPaymentMethod()).isEqualTo("CARD");
        assertThat(order.getShippingAddress()).isEqualTo("서울시 강남구");
        assertThat(order.getRecipientName()).isEqualTo("홍길동");
        assertThat(order.getRecipientPhone()).isEqualTo("010-1234-5678");
    }

    // ── OrderItem: 미커버 getter 검증 ──

    @Test
    @DisplayName("OrderItem — unitPrice, discountRate, cancelledAmount, createdAt getter")
    void orderItem_getters_covered() {
        OrderItem item = new OrderItem(1L, "상품A", 3,
                BigDecimal.valueOf(15000), BigDecimal.valueOf(0.10), BigDecimal.valueOf(45000));

        assertThat(item.getUnitPrice()).isEqualByComparingTo("15000");
        assertThat(item.getDiscountRate()).isEqualByComparingTo("0.10");
        assertThat(item.getCancelledAmount()).isEqualByComparingTo("0");
        assertThat(item.getCreatedAt()).isNotNull();
    }

    // ── OrderItemStatus: RETURN_APPROVED → 비RETURNED 대상 불허 ──

    @Test
    @DisplayName("RETURN_APPROVED → RETURN_REQUESTED: 불허")
    void returnApproved_toReturnRequested_forbidden() {
        assertThat(OrderItemStatus.RETURN_APPROVED.canTransitionTo(OrderItemStatus.RETURN_REQUESTED))
                .isFalse();
    }

    @Test
    @DisplayName("RETURN_APPROVED → CANCELLED: 불허")
    void returnApproved_toCancelled_forbidden() {
        assertThat(OrderItemStatus.RETURN_APPROVED.canTransitionTo(OrderItemStatus.CANCELLED))
                .isFalse();
    }

    @Test
    @DisplayName("RETURN_APPROVED → NORMAL: 불허")
    void returnApproved_toNormal_forbidden() {
        assertThat(OrderItemStatus.RETURN_APPROVED.canTransitionTo(OrderItemStatus.NORMAL))
                .isFalse();
    }

    @Test
    @DisplayName("RETURN_REJECTED → 자기 자신 전이: 불허")
    void returnRejected_toSelf_forbidden() {
        assertThat(OrderItemStatus.RETURN_REJECTED.canTransitionTo(OrderItemStatus.RETURN_REJECTED))
                .isFalse();
    }
}
