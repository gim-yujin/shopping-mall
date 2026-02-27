package com.shop.domain.order.service;

import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.point.repository.PointHistoryRepository;
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
}
