package com.shop.domain.order.service;

import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OrderService(파사드) 단위 테스트.
 *
 * 파사드가 올바르게 위임하는지, updateOrderStatus의 상태 전이 규칙이 정확한지 검증한다.
 * 취소 내부 로직(재고 복구, 포인트 환불 등)은 OrderCancellationServiceUnitTest에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock private OrderCreationService creationService;
    @Mock private OrderCancellationService cancellationService;
    @Mock private OrderQueryService queryService;
    @Mock private ShippingFeeCalculator shippingFeeCalculator;
    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(creationService, cancellationService,
                queryService, shippingFeeCalculator, orderRepository,
                userRepository, pointHistoryRepository);
    }

    // ── getOrderDetail 위임 테스트 ──────────────────────────

    @Test
    @DisplayName("getOrderDetail — queryService에 위임하여 조회 성공")
    void getOrderDetail_success() {
        Order order = mock(Order.class);
        when(queryService.getOrderDetail(1L, 10L)).thenReturn(order);

        Order result = orderService.getOrderDetail(1L, 10L);

        assertThat(result).isSameAs(order);
        verify(queryService).getOrderDetail(1L, 10L);
    }

    @Test
    @DisplayName("getOrderDetail — 주문 없으면 ResourceNotFoundException")
    void getOrderDetail_notFound_throwsException() {
        when(queryService.getOrderDetail(999L, 10L))
                .thenThrow(new ResourceNotFoundException("주문", 999L));

        assertThatThrownBy(() -> orderService.getOrderDetail(999L, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getOrderDetail — 다른 사용자의 주문이면 조회 불가")
    void getOrderDetail_wrongUser_throwsException() {
        when(queryService.getOrderDetail(1L, 99L))
                .thenThrow(new ResourceNotFoundException("주문", 1L));

        assertThatThrownBy(() -> orderService.getOrderDetail(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── updateOrderStatus 상태 전이 테스트 ──────────────────

    @Test
    @DisplayName("관리자 CANCELLED 상태 변경 시 cancellationService.cancelOrderInternal에 위임")
    void updateOrderStatus_cancelled_delegatesToCancellationService() {
        Long orderId = 1L;
        Long orderOwnerId = 101L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.PAID);
        when(order.isCancellable()).thenReturn(true);
        when(order.getUserId()).thenReturn(orderOwnerId);

        orderService.updateOrderStatus(orderId, "CANCELLED");

        verify(cancellationService).cancelOrderInternal(order, orderOwnerId);
    }

    @Test
    @DisplayName("관리자 CANCELLED 상태 변경 시 DELIVERED -> CANCELLED 전이는 차단")
    void updateOrderStatus_cancelled_disallowAfterDelivered() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.DELIVERED);

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "CANCELLED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("허용되지 않는 주문 상태 전이");

        verify(order, never()).cancel();
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("관리자 상태 변경 시 PAID -> SHIPPED 전이는 허용")
    void updateOrderStatus_allowPaidToShipped() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.PAID);

        orderService.updateOrderStatus(orderId, "SHIPPED");

        verify(order).markShipped();
    }

    @Test
    @DisplayName("관리자 상태 변경 시 SHIPPED -> PAID 전이는 차단")
    void updateOrderStatus_blockShippedToPaid() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "PAID"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("허용되지 않는 주문 상태 전이");

        verify(order, never()).markPaid();
    }

    @Test
    @DisplayName("관리자 상태 변경 시 PAID -> CANCELLED 전이는 isCancellable 규칙을 따른다")
    void updateOrderStatus_cancelled_followsIsCancellableRule() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.PAID);
        when(order.isCancellable()).thenReturn(false);

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "CANCELLED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소할 수 없는 주문 상태");

        verify(order, never()).cancel();
        verifyNoInteractions(cancellationService);
    }

    @Test
    @DisplayName("관리자 상태 변경 시 지원하지 않는 상태 코드는 차단")
    void updateOrderStatus_blockUnknownStatusCode() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "INVALID"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잘못된 주문 상태");

        verify(order, never()).markPaid();
        verify(order, never()).markShipped();
        verify(order, never()).markDelivered();
        verify(order, never()).cancel();
    }

    // ── settleEarnedPoints (DELIVERED 시 포인트 정산) ─────────────

    /**
     * [P0 FIX 검증] SHIPPED → DELIVERED 전이 시 settleEarnedPoints 호출 경로 검증.
     *
     * updateOrderStatus에서 DELIVERED 전이 시:
     * 1) order.markDelivered() 호출
     * 2) settleEarnedPoints() → user.addPoints(earned) + pointHistory 저장 + order.settlePoints()
     */
    @Test
    @DisplayName("updateOrderStatus DELIVERED — 포인트 적립 + 이력 저장 + settlePoints 호출")
    void updateOrderStatus_delivered_settlesEarnedPoints() {
        Long orderId = 1L;
        Long userId = 101L;
        int earned = 1500;

        Order order = mock(Order.class);
        User user = mock(User.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.SHIPPED);
        when(order.isPointsSettled()).thenReturn(false);
        when(order.getEarnedPointsSnapshot()).thenReturn(earned);
        when(order.getUserId()).thenReturn(userId);
        when(order.getOrderId()).thenReturn(orderId);
        when(order.getOrderNumber()).thenReturn("ORD-TEST");
        when(userRepository.findByIdWithLockAndTier(userId)).thenReturn(Optional.of(user));
        when(user.getUserId()).thenReturn(userId);
        when(user.getPointBalance()).thenReturn(earned);

        orderService.updateOrderStatus(orderId, "DELIVERED");

        verify(order).markDelivered();
        verify(user).addPoints(earned);
        verify(pointHistoryRepository).save(any(PointHistory.class));
        verify(order).settlePoints();
    }

    /**
     * [P0 FIX 검증] 이미 정산된 주문에 대해 중복 DELIVERED 호출 시 멱등성 보장.
     *
     * points_settled=true인 주문에 DELIVERED를 다시 호출하면
     * 포인트 적립/이력 저장이 수행되지 않아야 한다.
     */
    @Test
    @DisplayName("updateOrderStatus DELIVERED — 이미 정산된 주문은 포인트 미중복적립 (멱등성)")
    void updateOrderStatus_delivered_alreadySettled_isIdempotent() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.DELIVERED);
        when(order.isPointsSettled()).thenReturn(true);

        orderService.updateOrderStatus(orderId, "DELIVERED");

        verify(order).markDelivered();
        // 이미 정산 완료이므로 포인트 관련 로직이 실행되면 안 됨
        verify(userRepository, never()).findByIdWithLockAndTier(any());
        verify(pointHistoryRepository, never()).save(any());
        verify(order, never()).settlePoints();
    }

    /**
     * [P0 FIX 검증] earnedPointsSnapshot이 0인 경우에도 settlePoints()는 호출되어야 한다.
     *
     * 등급/금액에 따라 적립 포인트가 0일 수 있다.
     * 이 경우에도 points_settled 플래그는 true로 설정되어야
     * 이후 중복 체크에서 정상 동작한다.
     */
    @Test
    @DisplayName("updateOrderStatus DELIVERED — 적립 0P일 때도 settlePoints 호출")
    void updateOrderStatus_delivered_zeroEarned_stillSettles() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn(OrderStatus.SHIPPED);
        when(order.isPointsSettled()).thenReturn(false);
        when(order.getEarnedPointsSnapshot()).thenReturn(0);

        orderService.updateOrderStatus(orderId, "DELIVERED");

        verify(order).markDelivered();
        // earned=0이므로 user.addPoints는 호출되지 않지만
        verify(userRepository, never()).findByIdWithLockAndTier(any());
        verify(pointHistoryRepository, never()).save(any());
        // settlePoints()는 반드시 호출되어 플래그를 true로 전환
        verify(order).settlePoints();
    }
}
