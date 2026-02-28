package com.shop.domain.order.entity;
import com.shop.domain.order.entity.OrderStatus;

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
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(3000), BigDecimal.valueOf(53000),
                BigDecimal.valueOf(1.50), 795,
                0,
                "CARD", "서울시 강남구", "홍길동", "010-1234-5678");
    }

    // ==================== 초기 상태 ====================

    @Test
    @DisplayName("생성 직후 상태는 PENDING이다")
    void newOrder_statusIsPending() {
        Order order = createOrder();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getOrderDate()).isNotNull();
        assertThat(order.getPointEarnRateSnapshot()).isEqualByComparingTo("1.5");
        assertThat(order.getEarnedPointsSnapshot()).isEqualTo(795);
        assertThat(order.getUsedPoints()).isEqualTo(0);
    }

    // ==================== 상태 전이 ====================

    @Test
    @DisplayName("markPaid — 상태를 PAID로 변경하고 paidAt 기록")
    void markPaid_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("markShipped — 상태를 SHIPPED로 변경하고 shippedAt 기록")
    void markShipped_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isNotNull();
    }

    @Test
    @DisplayName("markDelivered — 상태를 DELIVERED로 변경하고 deliveredAt 기록")
    void markDelivered_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.markPaid();
        order.markShipped();
        order.markDelivered();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel — 상태를 CANCELLED로 변경하고 cancelledAt 기록")
    void cancel_changesStatusAndTimestamp() {
        Order order = createOrder();
        order.cancel();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
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

    // ==================== pointsSettled ====================

    /**
     * [P0 FIX 검증] 주문 생성 시 points_settled는 false로 초기화된다.
     * 포인트 적립은 배송 완료(DELIVERED) 시점에 수행되므로,
     * 생성 직후에는 항상 미정산 상태여야 한다.
     */
    @Test
    @DisplayName("pointsSettled — 주문 생성 시 false 초기화")
    void pointsSettled_defaultFalse() {
        Order order = createOrder();
        assertThat(order.isPointsSettled()).isFalse();
    }

    /**
     * [P0 FIX 검증] settlePoints() 호출 시 정산 플래그가 true로 전환된다.
     */
    @Test
    @DisplayName("settlePoints — 호출 후 isPointsSettled() == true")
    void settlePoints_setsFlag() {
        Order order = createOrder();
        order.settlePoints();
        assertThat(order.isPointsSettled()).isTrue();
    }

    /**
     * [P0 FIX 검증] settlePoints()는 멱등하다.
     * 이미 true인 상태에서 다시 호출해도 예외 없이 true를 유지한다.
     */
    @Test
    @DisplayName("settlePoints — 중복 호출 시 멱등성 보장")
    void settlePoints_idempotent() {
        Order order = createOrder();
        order.settlePoints();
        order.settlePoints(); // 두 번째 호출
        assertThat(order.isPointsSettled()).isTrue();
    }
}
