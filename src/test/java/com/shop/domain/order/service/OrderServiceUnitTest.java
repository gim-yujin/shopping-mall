package com.shop.domain.order.service;

import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OrderService 추가 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CartRepository cartRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductInventoryHistoryRepository inventoryHistoryRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private UserTierRepository userTierRepository;
    @Mock private EntityManager entityManager;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, cartRepository, productRepository,
                userRepository, inventoryHistoryRepository, userCouponRepository,
                userTierRepository, entityManager);
    }

    @Test
    @DisplayName("getOrderDetail — 주문 ID + 사용자 ID로 조회 성공")
    void getOrderDetail_success() {
        Order order = mock(Order.class);
        when(orderRepository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.of(order));

        Order result = orderService.getOrderDetail(1L, 10L);

        assertThat(result).isSameAs(order);
        verify(orderRepository).findByIdAndUserId(1L, 10L);
    }

    @Test
    @DisplayName("getOrderDetail — 주문 없으면 ResourceNotFoundException")
    void getOrderDetail_notFound_throwsException() {
        when(orderRepository.findByIdAndUserId(999L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderDetail(999L, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getOrderDetail — 다른 사용자의 주문이면 조회 불가")
    void getOrderDetail_wrongUser_throwsException() {
        when(orderRepository.findByIdAndUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderDetail(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("관리자 CANCELLED 상태 변경 시 주문 소유자 기준으로 재고/쿠폰/포인트 복구")
    void updateOrderStatus_cancelled_restoresByOrderOwner() {
        Long orderId = 1L;
        Long orderOwnerId = 101L;

        Order order = mock(Order.class);
        OrderItem item = mock(OrderItem.class);
        Product product = mock(Product.class);
        User user = mock(User.class);
        UserCoupon userCoupon = mock(UserCoupon.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.isCancellable()).thenReturn(true);
        when(order.getOrderId()).thenReturn(orderId);
        when(order.getUserId()).thenReturn(orderOwnerId);
        when(order.getFinalAmount()).thenReturn(new BigDecimal("10000"));
        when(order.getItems()).thenReturn(List.of(item));

        when(item.getProductId()).thenReturn(7L);
        when(item.getQuantity()).thenReturn(2);

        when(productRepository.findByIdWithLock(7L)).thenReturn(Optional.of(product));
        when(product.getProductId()).thenReturn(7L);
        when(product.getStockQuantity()).thenReturn(5, 7);

        when(userRepository.findById(orderOwnerId)).thenReturn(Optional.of(user));
        when(order.getEarnedPointsSnapshot()).thenReturn(100);

        when(userCouponRepository.findByOrderId(orderId)).thenReturn(Optional.of(userCoupon));

        orderService.updateOrderStatus(orderId, "CANCELLED");

        verify(product).increaseStock(2);
        verify(user).addTotalSpent(new BigDecimal("-10000"));
        verify(user).addPoints(-100);
        verify(userCoupon).cancelUse();
        verify(order).cancel();
    }

    @Test
    @DisplayName("관리자 CANCELLED 상태 변경 시 DELIVERED -> CANCELLED 전이는 차단")
    void updateOrderStatus_cancelled_disallowAfterDelivered() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn("DELIVERED");

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "CANCELLED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("허용되지 않는 주문 상태 전이");

        verify(order, never()).cancel();
        verifyNoInteractions(productRepository, userRepository, userCouponRepository);
    }

    @Test
    @DisplayName("관리자 상태 변경 시 PAID -> SHIPPED 전이는 허용")
    void updateOrderStatus_allowPaidToShipped() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn("PAID");

        orderService.updateOrderStatus(orderId, "SHIPPED");

        verify(order).markShipped();
    }

    @Test
    @DisplayName("관리자 상태 변경 시 SHIPPED -> PAID 전이는 차단")
    void updateOrderStatus_blockShippedToPaid() {
        Long orderId = 1L;
        Order order = mock(Order.class);

        when(orderRepository.findByIdWithLock(orderId)).thenReturn(Optional.of(order));
        when(order.getOrderStatus()).thenReturn("SHIPPED");

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
        when(order.getOrderStatus()).thenReturn("PAID");
        when(order.isCancellable()).thenReturn(false);

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, "CANCELLED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소할 수 없는 주문 상태");

        verify(order, never()).cancel();
    }
}
