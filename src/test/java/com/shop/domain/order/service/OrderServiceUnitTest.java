package com.shop.domain.order.service;

import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
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
 * OrderService 추가 단위 테스트 — getOrderDetail
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
}
