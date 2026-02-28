package com.shop.domain.order.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.event.ProductStockChangedEvent;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PartialCancellationService 단위 테스트.
 *
 * 리뷰 2에서 발견된 P0/P1 이슈 수정 후, 다음 항목을 검증한다:
 * - P0-1: 환불 금액이 finalAmount 비례로 계산되는지 (과다 환불 방지)
 * - P0-2: 포인트 비례 환불 + PointHistory 기록
 * - P0-3: 등급 재계산 수행
 * - P1-1: Order 비관적 잠금으로 refundedAmount lost update 방지
 * - P1-2: 락 순서 통일 (Order → Product → User)
 * - P1-3: ProductStockChangedEvent 발행 (캐시 무효화)
 * - P1-보너스: 전체 아이템 취소 시 주문 상태 CANCELLED 전이
 */
@ExtendWith(MockitoExtension.class)
class PartialCancellationServiceUnitTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductInventoryHistoryRepository inventoryHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserTierRepository userTierRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private EntityManager entityManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PartialCancellationService service;

    @BeforeEach
    void setUp() {
        service = new PartialCancellationService(
                orderRepository, productRepository, inventoryHistoryRepository,
                userRepository, userTierRepository, pointHistoryRepository,
                entityManager, eventPublisher);
    }

    // ── 테스트 픽스처 빌더 ──────────────────────────────────

    /**
     * 주문 시나리오:
     *   상품A: 10,000원 × 3개 = 30,000원
     *   상품B: 20,000원 × 2개 = 40,000원
     *   totalAmount = 70,000원
     *   tierDiscount = 3,500원 (5%)
     *   couponDiscount = 5,000원
     *   usedPoints = 1,000P
     *   shippingFee = 3,000원
     *   finalAmount = 70,000 - 3,500 - 5,000 - 1,000 + 3,000 = 63,500원
     *   effectivePaid (배송비 제외) = 60,500원
     */
    private Order createTestOrder(OrderStatus status) {
        Order order = mock(Order.class);
        OrderItem itemA = createMockItem(100L, 10L, "상품A", 3,
                new BigDecimal("10000"), new BigDecimal("30000"));
        OrderItem itemB = createMockItem(200L, 20L, "상품B", 2,
                new BigDecimal("20000"), new BigDecimal("40000"));

        lenient().when(order.getOrderId()).thenReturn(1L);
        lenient().when(order.getUserId()).thenReturn(101L);
        lenient().when(order.getOrderNumber()).thenReturn("ORD-TEST-001");
        lenient().when(order.getOrderStatus()).thenReturn(status);
        lenient().when(order.getTotalAmount()).thenReturn(new BigDecimal("70000"));
        lenient().when(order.getDiscountAmount()).thenReturn(new BigDecimal("8500"));
        lenient().when(order.getShippingFee()).thenReturn(new BigDecimal("3000"));
        lenient().when(order.getFinalAmount()).thenReturn(new BigDecimal("63500"));
        lenient().when(order.getUsedPoints()).thenReturn(1000);
        lenient().when(order.getRefundedAmount()).thenReturn(BigDecimal.ZERO);
        lenient().when(order.getRefundedPoints()).thenReturn(0);
        lenient().when(order.getItems()).thenReturn(List.of(itemA, itemB));

        if (status == OrderStatus.PENDING || status == OrderStatus.PAID) {
            lenient().when(order.isCancellable()).thenReturn(true);
        } else {
            lenient().when(order.isCancellable()).thenReturn(false);
        }

        return order;
    }

    private OrderItem createMockItem(Long itemId, Long productId, String name,
                                     int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
        OrderItem item = mock(OrderItem.class);
        lenient().when(item.getOrderItemId()).thenReturn(itemId);
        lenient().when(item.getProductId()).thenReturn(productId);
        lenient().when(item.getProductName()).thenReturn(name);
        lenient().when(item.getQuantity()).thenReturn(quantity);
        lenient().when(item.getUnitPrice()).thenReturn(unitPrice);
        lenient().when(item.getSubtotal()).thenReturn(subtotal);
        lenient().when(item.getCancelledQuantity()).thenReturn(0);
        lenient().when(item.getReturnedQuantity()).thenReturn(0);
        lenient().when(item.getRemainingQuantity()).thenReturn(quantity);
        return item;
    }

    private Product createMockProduct(Long productId, int stock) {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(productId);
        when(product.getStockQuantity()).thenReturn(stock, stock + 1); // before, after
        return product;
    }

    private User createMockUser(Long userId, int pointBalance, BigDecimal totalSpent) {
        User user = mock(User.class);
        lenient().when(user.getUserId()).thenReturn(userId);
        lenient().when(user.getPointBalance()).thenReturn(pointBalance);
        lenient().when(user.getTotalSpent()).thenReturn(totalSpent);
        return user;
    }

    // ── 부분 취소 정상 플로우 ────────────────────────────────

    @Nested
    @DisplayName("부분 취소 — 정상 플로우")
    class PartialCancelSuccess {

        @Test
        @DisplayName("[P0-1] 환불 금액이 finalAmount 비례로 계산된다 (과다 환불 방지)")
        void refundAmount_isProportionalToFinalAmount() {
            // given: 상품A(30,000원/70,000원 비중) 1개 부분 취소
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            // when
            service.partialCancel(101L, 1L, 100L, 1);

            // then: 환불 = (63,500 - 3,000) × (30,000/70,000) × (1/3) = 8,642.86
            //   기존 버그 코드는 30,000/3 = 10,000원으로 과다 환불
            ArgumentCaptor<BigDecimal> refundCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(order).addRefundedAmount(refundCaptor.capture());
            BigDecimal actualRefund = refundCaptor.getValue();

            BigDecimal expectedRefund = new BigDecimal("60500")
                    .multiply(new BigDecimal("30000"))
                    .divide(new BigDecimal("70000"), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

            assertThat(actualRefund).isEqualByComparingTo(expectedRefund);
            // 환불액 < 원가 기준 환불액(10,000원)을 확인
            assertThat(actualRefund).isLessThan(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("[P0-2] 포인트가 아이템 비중에 따라 비례 환불된다")
        void points_areRefundedProportionally() {
            // given: usedPoints=1000, 상품A(30,000/70,000 비중) 1개 취소
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            // when
            service.partialCancel(101L, 1L, 100L, 1);

            // then: 포인트 환불 = floor(1000 × (30,000/70,000) × (1/3)) = floor(142.857) = 142P
            int expectedPointRefund = new BigDecimal("1000")
                    .multiply(new BigDecimal("30000"))
                    .divide(new BigDecimal("70000"), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("3"), 0, RoundingMode.FLOOR)
                    .intValue();

            verify(user).addPoints(expectedPointRefund);
            verify(order).addRefundedPoints(expectedPointRefund);

            // PointHistory 기록 검증
            ArgumentCaptor<PointHistory> historyCaptor = ArgumentCaptor.forClass(PointHistory.class);
            verify(pointHistoryRepository).save(historyCaptor.capture());
            PointHistory history = historyCaptor.getValue();
            assertThat(history.getChangeType()).isEqualTo(PointHistory.REFUND);
            assertThat(history.getAmount()).isEqualTo(expectedPointRefund);
        }

        @Test
        @DisplayName("[P0-2] 포인트 미사용 주문은 포인트 환불이 발생하지 않는다")
        void noPoints_noPointRefund() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(order.getUsedPoints()).thenReturn(0);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 0, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(user, never()).addPoints(anyInt());
            verify(pointHistoryRepository, never()).save(any(PointHistory.class));
        }

        @Test
        @DisplayName("[P0-3] 부분 취소 후 등급이 재계산된다")
        void tier_isRecalculated() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));
            UserTier lowerTier = mock(UserTier.class);

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));
            when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any()))
                    .thenReturn(Optional.of(lowerTier));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(userTierRepository).findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any());
            verify(user).updateTier(lowerTier);
        }

        @Test
        @DisplayName("[P1-1] Order를 비관적 잠금으로 획득한다 (lost update 방지)")
        void order_isLockedPessimistically() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            // Order 비관적 잠금이 가장 먼저 호출되는지 확인
            verify(orderRepository).findByIdAndUserIdWithLock(1L, 101L);
        }

        @Test
        @DisplayName("[P1-2] 락 순서가 Order → Product → User이다 (교차 데드락 방지)")
        void lockOrder_isConsistentWithFullCancellation() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            // 호출 순서 검증: Order(잠금) → Product(잠금) → User(잠금)
            var inOrder = inOrder(orderRepository, productRepository, userRepository);
            inOrder.verify(orderRepository).findByIdAndUserIdWithLock(1L, 101L);
            inOrder.verify(productRepository).findByIdWithLock(10L);
            inOrder.verify(userRepository).findByIdWithLockAndTier(101L);
        }

        @Test
        @DisplayName("[P1-3] 재고 복구 후 ProductStockChangedEvent가 발행된다")
        void stockChangeEvent_isPublished() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(eventPublisher).publishEvent(new ProductStockChangedEvent(List.of(10L)));
        }

        @Test
        @DisplayName("재고가 복구되고 재고 이력이 기록된다")
        void stock_isRestoredWithHistory() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 2);

            verify(product).increaseStockAndRollbackSales(2);
            verify(entityManager).refresh(product);

            ArgumentCaptor<ProductInventoryHistory> histCaptor =
                    ArgumentCaptor.forClass(ProductInventoryHistory.class);
            verify(inventoryHistoryRepository).save(histCaptor.capture());
            ProductInventoryHistory hist = histCaptor.getValue();
            assertThat(hist.getChangeType()).isEqualTo("IN");
            assertThat(hist.getChangeAmount()).isEqualTo(2);
            assertThat(hist.getReason()).isEqualTo("PARTIAL_CANCEL");
        }
    }

    // ── 전체 아이템 취소 시 상태 전이 ─────────────────────────

    @Nested
    @DisplayName("전체 아이템 취소 시 주문 상태 전이")
    class FullyCancelledTransition {

        @Test
        @DisplayName("모든 아이템의 잔여 수량이 0이면 CANCELLED로 전이한다")
        void allItemsCancelled_transitionsToCancelled() {
            Order order = createTestOrder(OrderStatus.PAID);
            OrderItem itemA = order.getItems().get(0);
            OrderItem itemB = order.getItems().get(1);
            // 상품A: 3개 중 2개 이미 취소, 나머지 1개 취소 → 0
            when(itemA.getRemainingQuantity()).thenReturn(1, 0);
            // 상품B: 이미 전부 취소
            when(itemB.getRemainingQuantity()).thenReturn(0);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(order).cancel();
        }

        @Test
        @DisplayName("일부 아이템에 잔여 수량이 남아있으면 상태를 유지한다")
        void someItemsRemaining_doesNotTransition() {
            Order order = createTestOrder(OrderStatus.PAID);
            OrderItem itemA = order.getItems().get(0);
            OrderItem itemB = order.getItems().get(1);
            // 상품A: 1개 취소 후 2개 남음
            when(itemA.getRemainingQuantity()).thenReturn(3, 2);
            // 상품B: 전량 남음
            when(itemB.getRemainingQuantity()).thenReturn(2);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(order, never()).cancel();
        }
    }

    // ── 부분 취소 실패 케이스 ────────────────────────────────

    @Nested
    @DisplayName("부분 취소 — 실패 케이스")
    class PartialCancelFailure {

        @Test
        @DisplayName("취소 불가 상태(SHIPPED)에서 BusinessException 발생")
        void shippedOrder_throwsException() {
            Order order = createTestOrder(OrderStatus.SHIPPED);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.partialCancel(101L, 1L, 100L, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "PARTIAL_CANCEL_NOT_ALLOWED");

            verifyNoInteractions(productRepository, userRepository);
        }

        @Test
        @DisplayName("취소 불가 상태(CANCELLED)에서 BusinessException 발생")
        void cancelledOrder_throwsException() {
            Order order = createTestOrder(OrderStatus.CANCELLED);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.partialCancel(101L, 1L, 100L, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "PARTIAL_CANCEL_NOT_ALLOWED");
        }

        @Test
        @DisplayName("잔여 수량 초과 요청 시 BusinessException 발생")
        void exceedsRemainingQuantity_throwsException() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            // 상품A의 잔여 수량 = 3, 4개 취소 요청
            assertThatThrownBy(() -> service.partialCancel(101L, 1L, 100L, 4))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_PARTIAL_QUANTITY");
        }

        @Test
        @DisplayName("0개 취소 요청 시 BusinessException 발생")
        void zeroQuantity_throwsException() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.partialCancel(101L, 1L, 100L, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_PARTIAL_QUANTITY");
        }

        @Test
        @DisplayName("존재하지 않는 주문상품 ID 시 ResourceNotFoundException 발생")
        void invalidOrderItemId_throwsException() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.partialCancel(101L, 1L, 999L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("존재하지 않는 주문 시 ResourceNotFoundException 발생")
        void invalidOrderId_throwsException() {
            when(orderRepository.findByIdAndUserIdWithLock(999L, 101L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.partialCancel(101L, 999L, 100L, 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── 반품 신청 ──────────────────────────────────────────

    @Nested
    @DisplayName("반품 신청")
    class RequestReturn {

        @Test
        @DisplayName("DELIVERED 상태에서 반품이 성공한다")
        void deliveredOrder_returnSucceeds() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.requestReturn(101L, 1L, 100L, 1);

            // 반품 처리 확인
            verify(order.getItems().get(0)).applyReturn(eq(1), any(BigDecimal.class));
            verify(product).increaseStockAndRollbackSales(1);
            verify(eventPublisher).publishEvent(any(ProductStockChangedEvent.class));

            // 반품 사유가 재고 이력에 기록
            ArgumentCaptor<ProductInventoryHistory> histCaptor =
                    ArgumentCaptor.forClass(ProductInventoryHistory.class);
            verify(inventoryHistoryRepository).save(histCaptor.capture());
            assertThat(histCaptor.getValue().getReason()).isEqualTo("RETURN");
        }

        @Test
        @DisplayName("PENDING 상태에서 반품 시 BusinessException 발생")
        void pendingOrder_returnFails() {
            Order order = createTestOrder(OrderStatus.PENDING);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_NOT_ALLOWED");
        }

        @Test
        @DisplayName("PAID 상태에서 반품 시 BusinessException 발생")
        void paidOrder_returnFails() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_NOT_ALLOWED");
        }

        @Test
        @DisplayName("DELIVERED 반품 시에도 포인트가 비례 환불된다")
        void deliveredReturn_refundsPointsProportionally() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.requestReturn(101L, 1L, 100L, 1);

            // 포인트 환불 검증
            verify(user).addPoints(anyInt());
            verify(order).addRefundedPoints(anyInt());

            ArgumentCaptor<PointHistory> histCaptor = ArgumentCaptor.forClass(PointHistory.class);
            verify(pointHistoryRepository).save(histCaptor.capture());
            assertThat(histCaptor.getValue().getReferenceType()).isEqualTo("RETURN");
            assertThat(histCaptor.getValue().getDescription()).contains("반품");
        }

        @Test
        @DisplayName("DELIVERED 전체 아이템 반품 시 주문 상태는 유지된다 (CANCELLED로 전이하지 않음)")
        void allItemsReturned_statusRemains() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            OrderItem itemB = order.getItems().get(1);
            when(itemA.getRemainingQuantity()).thenReturn(1, 0);
            when(itemB.getRemainingQuantity()).thenReturn(0);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.requestReturn(101L, 1L, 100L, 1);

            // DELIVERED 상태에서는 cancel()이 호출되지 않는다
            verify(order, never()).cancel();
        }
    }

    // ── 안전 상한 검증 ──────────────────────────────────────

    @Nested
    @DisplayName("안전 상한 — 초과 환불 방지")
    class SafetyCaps {

        @Test
        @DisplayName("포인트 환불 누계가 usedPoints를 초과하지 않는다")
        void pointRefund_doesNotExceedUsedPoints() {
            Order order = createTestOrder(OrderStatus.PAID);
            // 이전 부분 취소로 이미 900P 환불됨
            when(order.getRefundedPoints()).thenReturn(900);
            when(order.getUsedPoints()).thenReturn(1000);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 900, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            // 상품A 3개 중 1개 취소 → 비례 환불 142P이지만, 잔여 100P만 환불 가능
            service.partialCancel(101L, 1L, 100L, 1);

            ArgumentCaptor<Integer> pointsCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(user).addPoints(pointsCaptor.capture());
            assertThat(pointsCaptor.getValue()).isLessThanOrEqualTo(100);
        }
    }
}
