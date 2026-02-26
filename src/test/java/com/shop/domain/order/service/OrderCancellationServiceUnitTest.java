package com.shop.domain.order.service;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderCancellationService 단위 테스트.
 *
 * OrderService(God Class) 분리 후 취소 내부 로직(재고 복구, 포인트 환불,
 * 쿠폰 복원, 등급 재계산)이 올바르게 동작하는지 검증한다.
 * 기존 OrderServiceUnitTest.updateOrderStatus_cancelled_restoresByOrderOwner에서 이전.
 */
@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceUnitTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductInventoryHistoryRepository inventoryHistoryRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private UserTierRepository userTierRepository;
    @Mock private EntityManager entityManager;
    @Mock private CacheManager cacheManager;

    private OrderCancellationService cancellationService;

    @BeforeEach
    void setUp() {
        cancellationService = new OrderCancellationService(
                orderRepository, productRepository, userRepository,
                inventoryHistoryRepository, userCouponRepository,
                userTierRepository, entityManager, cacheManager);
    }

    @Test
    @DisplayName("cancelOrderInternal — 재고 복구, 포인트 환불, 쿠폰 복원이 모두 수행된다")
    void cancelOrderInternal_restoresStockPointsCoupon() {
        Long orderId = 1L;
        Long userId = 101L;

        Order order = mock(Order.class);
        OrderItem item = mock(OrderItem.class);
        Product product = mock(Product.class);
        User user = mock(User.class);
        UserCoupon userCoupon = mock(UserCoupon.class);

        when(order.isCancellable()).thenReturn(true);
        when(order.getOrderId()).thenReturn(orderId);
        when(order.getFinalAmount()).thenReturn(new BigDecimal("10000"));
        when(order.getItems()).thenReturn(List.of(item));
        when(order.getEarnedPointsSnapshot()).thenReturn(100);
        when(order.getUsedPoints()).thenReturn(0);

        when(item.getProductId()).thenReturn(7L);
        when(item.getQuantity()).thenReturn(2);

        when(productRepository.findByIdWithLock(7L)).thenReturn(Optional.of(product));
        when(product.getProductId()).thenReturn(7L);
        when(product.getStockQuantity()).thenReturn(5, 7);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userCouponRepository.findByOrderId(orderId)).thenReturn(Optional.of(userCoupon));
        when(user.getTotalSpent()).thenReturn(BigDecimal.ZERO);
        when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any()))
                .thenReturn(Optional.empty());

        cancellationService.cancelOrderInternal(order, userId);

        verify(product).increaseStockAndRollbackSales(2);
        verify(user).addTotalSpent(new BigDecimal("-10000"));
        verify(user).addPoints(-100);
        verify(userCoupon).cancelUse();
        verify(order).cancel();
    }
}
