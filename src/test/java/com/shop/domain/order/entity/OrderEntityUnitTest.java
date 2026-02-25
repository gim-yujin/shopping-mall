package com.shop.domain.order.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Order 엔티티 단위 테스트 — 상태 전이, 취소 가능 판별, 아이템 관리
 */
class OrderEntityUnitTest {

    private Order createOrder() {
        return new Order("ORD-001", 1L,
                BigDecimal.valueOf(50000), BigDecimal.ZERO,
                BigDecimal.valueOf(3000), BigDecimal.valueOf(53000),
                BigDecimal.valueOf(1.50), 795,
                "CARD", "서울시 강남구", "홍길동", "010-1234-5678");
    }

    // ==================== 초기 상태 ====================

    @Test
    @DisplayName("생성 직후 상태는 PENDING이다")
    void newOrder_statusIsPending() {
        Order order = createOrder();
        assertThat(order.getOrderStatus()).isEqualTo("PENDING");
        assertThat(order.getOrderDate()).isNotNull();
        assertThat(order.getPointEarnRateSnapshot()).isEqualByComparingTo("1.5");
        assertThat(order.getEarnedPointsSnapshot()).isEqualTo(795);
    }

    // ==================== 상태 전이 ====================

    @Test
    @DisplayName("markPaid — 상태를 PAID로 변경하고 paidAt 기록")
    void markPaid_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();

        assertThat(order.getOrderStatus()).isEqualTo("PAID");
        assertThat(order.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("markShipped — 상태를 SHIPPED로 변경하고 shippedAt 기록")
    void markShipped_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();

        assertThat(order.getOrderStatus()).isEqualTo("SHIPPED");
        assertThat(order.getShippedAt()).isNotNull();
    }

    @Test
    @DisplayName("markDelivered — 상태를 DELIVERED로 변경하고 deliveredAt 기록")
    void markDelivered_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();
        order.markDelivered();

        assertThat(order.getOrderStatus()).isEqualTo("DELIVERED");
        assertThat(order.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel — 상태를 CANCELLED로 변경하고 cancelledAt 기록")
    void cancel_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.cancel();

        assertThat(order.getOrderStatus()).isEqualTo("CANCELLED");
        assertThat(order.getCancelledAt()).isNotNull();
    }

    // ==================== isCancellable ====================

    @Test
    @DisplayName("isCancellable — PENDING 상태에서 취소 가능")
    void isCancellable_pendingReturnsTrue() {
        Order order = createOrder();
        assertThat(order.isCancellable()).isTrue();
    }

    @Test
    @DisplayName("isCancellable — PAID 상태에서 취소 가능")
    void isCancellable_paidReturnsTrue() {
        Order order = createOrder();
        order.markPaid();
        assertThat(order.isCancellable()).isTrue();
    }

    @Test
    @DisplayName("isCancellable — SHIPPED 이후에는 취소 불가")
    void isCancellable_shippedReturnsFalse() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();
        assertThat(order.isCancellable()).isFalse();
    }

    @Test
    @DisplayName("isCancellable — DELIVERED는 취소 불가")
    void isCancellable_deliveredReturnsFalse() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();
        order.markDelivered();
        assertThat(order.isCancellable()).isFalse();
    }

    @Test
    @DisplayName("isCancellable — CANCELLED는 취소 불가")
    void isCancellable_cancelledReturnsFalse() {
        Order order = createOrder();
        order.cancel();
        assertThat(order.isCancellable()).isFalse();
    }

    // ==================== addItem ====================

    @Test
    @DisplayName("addItem — 주문 항목 추가 시 양방향 관계 설정")
    void addItem_setsUpBidirectionalRelationship() {
        Order order = createOrder();
        OrderItem item = new OrderItem(1L, "상품A", 2,
                BigDecimal.valueOf(10000), BigDecimal.ZERO, BigDecimal.valueOf(20000));

        order.addItem(item);

        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getItems().get(0).getProductName()).isEqualTo("상품A");
        assertThat(item.getOrder()).isSameAs(order);
    }

    @Test
    @DisplayName("addItem — 여러 항목 추가")
    void addItem_multipleItems() {
        Order order = createOrder();
        order.addItem(new OrderItem(1L, "상품A", 1,
                BigDecimal.valueOf(10000), BigDecimal.ZERO, BigDecimal.valueOf(10000)));
        order.addItem(new OrderItem(2L, "상품B", 3,
                BigDecimal.valueOf(5000), BigDecimal.ZERO, BigDecimal.valueOf(15000)));

        assertThat(order.getItems()).hasSize(2);
    }

    // ==================== getStatusDisplay ====================

    @Test
    @DisplayName("getStatusDisplay — 각 상태별 한글 표시")
    void getStatusDisplay_returnsKoreanLabel() {
        Order order = createOrder();
        assertThat(order.getStatusDisplay()).isEqualTo("결제대기");

        order.markPaid();
        assertThat(order.getStatusDisplay()).isEqualTo("결제완료");

        order.markShipped();
        assertThat(order.getStatusDisplay()).isEqualTo("배송중");

        order.markDelivered();
        assertThat(order.getStatusDisplay()).isEqualTo("배송완료");
    }

    @Test
    @DisplayName("getStatusDisplay — 취소 상태 표시")
    void getStatusDisplay_cancelled() {
        Order order = createOrder();
        order.cancel();
        assertThat(order.getStatusDisplay()).isEqualTo("주문취소");
    }
}
