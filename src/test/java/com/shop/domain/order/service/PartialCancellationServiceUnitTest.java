package com.shop.domain.order.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PartialCancellationService 단위 테스트.
 *
 * <h3>Step 2 변경: requestReturn → 신청만 / approveReturn → 환불 / rejectReturn → 거절</h3>
 *
 * <p>기존 requestReturn 테스트는 즉시 환불을 검증했으나, Step 2에서는 상태 전이만
 * 수행하고 환불이 발생하지 않음을 검증한다. 환불 검증은 approveReturn 테스트로 이동했다.</p>
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>부분 취소: P0-1 비례 환불, P0-2 포인트 환불, P0-3 등급 재계산,
 *       P1-1 Order 잠금, P1-2 락 순서, P1-3 캐시 무효화 (기존 유지)</li>
 *   <li>반품 신청: 상태 전이만 수행, 재고/환불 변경 없음, 반품 사유 기록</li>
 *   <li>반품 승인: 재고 복구, 비례 환불, 포인트 환불, 등급 재계산, 캐시 무효화</li>
 *   <li>반품 거절: 상태 전이만 수행, 재고/환불 변경 없음, 거절 사유 기록</li>
 *   <li>전체 아이템 취소 시 주문 상태 CANCELLED 전이</li>
 * </ul>
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

        // DELIVERED 상태인 경우 반품 기간 검증을 위해 deliveredAt 설정 (기본: 방금 배송완료)
        if (status == OrderStatus.DELIVERED) {
            lenient().when(order.getDeliveredAt()).thenReturn(LocalDateTime.now());
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
        lenient().when(item.getStatus()).thenReturn(OrderItemStatus.NORMAL);
        lenient().when(item.getPendingReturnQuantity()).thenReturn(0);
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

    // ═══════════════════════════════════════════════════════════
    // 부분 취소 — 정상 플로우 (기존 테스트 유지)
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("부분 취소 — 정상 플로우")
    class PartialCancelSuccess {

        @Test
        @DisplayName("[P0-1] 환불 금액이 finalAmount 비례로 계산된다 (과다 환불 방지)")
        void refundAmount_isProportionalToFinalAmount() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            // 환불 = (63,500 - 3,000) × (30,000/70,000) × (1/3) = 8,642.86
            ArgumentCaptor<BigDecimal> refundCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(order).addRefundedAmount(refundCaptor.capture());
            BigDecimal actualRefund = refundCaptor.getValue();

            BigDecimal expectedRefund = new BigDecimal("60500")
                    .multiply(new BigDecimal("30000"))
                    .divide(new BigDecimal("70000"), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

            assertThat(actualRefund).isEqualByComparingTo(expectedRefund);
            assertThat(actualRefund).isLessThan(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("[P0-2] 포인트가 아이템 비중에 따라 비례 환불된다")
        void points_areRefundedProportionally() {
            Order order = createTestOrder(OrderStatus.PAID);
            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            int expectedPointRefund = new BigDecimal("1000")
                    .multiply(new BigDecimal("30000"))
                    .divide(new BigDecimal("70000"), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("3"), 0, RoundingMode.FLOOR)
                    .intValue();

            verify(user).addPoints(expectedPointRefund);
            verify(order).addRefundedPoints(expectedPointRefund);

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

    // ═══════════════════════════════════════════════════════════
    // 전체 아이템 취소 시 상태 전이 (기존 테스트 유지)
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("전체 아이템 취소 시 주문 상태 전이")
    class FullyCancelledTransition {

        @Test
        @DisplayName("모든 아이템의 잔여 수량이 0이면 CANCELLED로 전이한다")
        void allItemsCancelled_transitionsToCancelled() {
            Order order = createTestOrder(OrderStatus.PAID);
            OrderItem itemA = order.getItems().get(0);
            OrderItem itemB = order.getItems().get(1);
            when(itemA.getRemainingQuantity()).thenReturn(1, 0);
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
            when(itemA.getRemainingQuantity()).thenReturn(3, 2);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            verify(order, never()).cancel();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 부분 취소 — 실패 케이스 (기존 테스트 유지)
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // 반품 신청 — Step 2: 상태 전이만 수행, 환불 없음
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("반품 신청 (Step 2: 상태 전이만)")
    class RequestReturn {

        @Test
        @DisplayName("[Step 2] DELIVERED 상태에서 반품 신청 시 상태만 전이한다 (재고/환불 없음)")
        void deliveredOrder_requestReturn_onlyTransitionsState() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            OrderItem itemA = order.getItems().get(0);

            service.requestReturn(101L, 1L, 100L, 1, "DEFECT");

            // OrderItem.requestReturn이 호출되어 상태 전이 + 반품 사유 기록
            verify(itemA).requestReturn(1, "DEFECT");

            // 재고 복구가 발생하지 않음 (Product 조회 자체가 없어야 함)
            verifyNoInteractions(productRepository);
            // User 조회도 발생하지 않음 (포인트/등급 변경 없음)
            verifyNoInteractions(userRepository);
            // 캐시 무효화 이벤트도 발행되지 않음
            verifyNoInteractions(eventPublisher);
            // PointHistory 기록 없음
            verifyNoInteractions(pointHistoryRepository);
        }

        @Test
        @DisplayName("[Step 2] 반품 신청 후 Order.addRefundedAmount가 호출되지 않는다")
        void requestReturn_doesNotUpdateOrderRefund() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            service.requestReturn(101L, 1L, 100L, 2, "WRONG_ITEM");

            verify(order, never()).addRefundedAmount(any());
            verify(order, never()).addRefundedPoints(anyInt());
        }

        @Test
        @DisplayName("PENDING 상태에서 반품 시 BusinessException 발생")
        void pendingOrder_returnFails() {
            Order order = createTestOrder(OrderStatus.PENDING);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1, "DEFECT"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_NOT_ALLOWED");
        }

        @Test
        @DisplayName("PAID 상태에서 반품 시 BusinessException 발생")
        void paidOrder_returnFails() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1, "DEFECT"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_NOT_ALLOWED");
        }

        @Test
        @DisplayName("[P2-9] 반품 기간 내(14일 이내) 반품 신청은 정상 접수된다")
        void withinReturnPeriod_succeeds() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(order.getDeliveredAt()).thenReturn(
                    LocalDateTime.now().minusDays(PartialCancellationService.RETURN_PERIOD_DAYS - 1));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatCode(() -> service.requestReturn(101L, 1L, 100L, 1, "DEFECT"))
                    .doesNotThrowAnyException();

            verify(order.getItems().get(0)).requestReturn(1, "DEFECT");
        }

        @Test
        @DisplayName("[P2-9] 반품 기간 초과(15일 경과) 시 RETURN_PERIOD_EXPIRED 예외 발생")
        void afterReturnPeriod_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(order.getDeliveredAt()).thenReturn(
                    LocalDateTime.now().minusDays(PartialCancellationService.RETURN_PERIOD_DAYS + 1));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1, "DEFECT"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_PERIOD_EXPIRED");

            verifyNoInteractions(productRepository, userRepository);
        }

        @Test
        @DisplayName("[P2-9] 반품 마감일 당일은 반품이 허용된다 (경계값)")
        void exactlyOnDeadline_succeeds() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(order.getDeliveredAt()).thenReturn(
                    LocalDateTime.now().minusDays(PartialCancellationService.RETURN_PERIOD_DAYS).plusHours(1));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatCode(() -> service.requestReturn(101L, 1L, 100L, 1, "CHANGE_OF_MIND"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("[P2-9] deliveredAt이 null이면 반품이 거부된다 (데이터 정합성 방어)")
        void nullDeliveredAt_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(order.getDeliveredAt()).thenReturn(null);

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestReturn(101L, 1L, 100L, 1, "DEFECT"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "RETURN_NOT_ALLOWED");

            verifyNoInteractions(productRepository, userRepository);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 관리자 반품 승인 — Step 2 신규
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("관리자 반품 승인 (Step 2 신규)")
    class ApproveReturn {

        @Test
        @DisplayName("RETURN_REQUESTED 상태에서 승인 시 재고 복구 + 환불이 실행된다")
        void approveReturn_executesRefund() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            // RETURN_REQUESTED 상태, pendingReturnQuantity = 2
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(2);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            // 재고 복구 확인
            verify(product).increaseStockAndRollbackSales(2);
            verify(entityManager).refresh(product);

            // 재고 이력 기록 확인
            ArgumentCaptor<ProductInventoryHistory> histCaptor =
                    ArgumentCaptor.forClass(ProductInventoryHistory.class);
            verify(inventoryHistoryRepository).save(histCaptor.capture());
            assertThat(histCaptor.getValue().getReason()).isEqualTo("RETURN");
            assertThat(histCaptor.getValue().getChangeAmount()).isEqualTo(2);

            // OrderItem 상태 전이 확인
            verify(itemA).approveReturn(eq(2), any(BigDecimal.class));

            // 캐시 무효화 이벤트 발행 확인
            verify(eventPublisher).publishEvent(any(ProductStockChangedEvent.class));
        }

        @Test
        @DisplayName("승인 시 환불 금액이 finalAmount 비례로 계산된다")
        void approveReturn_refundIsProportional() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            // 환불 = (63,500 - 3,000) × (30,000/70,000) × (1/3) = 8,642.86
            BigDecimal expectedRefund = new BigDecimal("60500")
                    .multiply(new BigDecimal("30000"))
                    .divide(new BigDecimal("70000"), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

            // order.addRefundedAmount로 검증
            ArgumentCaptor<BigDecimal> refundCaptor = ArgumentCaptor.forClass(BigDecimal.class);
            verify(order).addRefundedAmount(refundCaptor.capture());
            assertThat(refundCaptor.getValue()).isEqualByComparingTo(expectedRefund);
        }

        @Test
        @DisplayName("승인 시 포인트가 비례 환불된다")
        void approveReturn_refundsPointsProportionally() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            verify(user).addPoints(anyInt());
            verify(order).addRefundedPoints(anyInt());

            ArgumentCaptor<PointHistory> histCaptor = ArgumentCaptor.forClass(PointHistory.class);
            verify(pointHistoryRepository).save(histCaptor.capture());
            assertThat(histCaptor.getValue().getReferenceType()).isEqualTo("RETURN");
            assertThat(histCaptor.getValue().getDescription()).contains("반품");
        }

        @Test
        @DisplayName("승인 시 등급이 재계산된다")
        void approveReturn_recalculatesTier() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));
            UserTier lowerTier = mock(UserTier.class);

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));
            when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any()))
                    .thenReturn(Optional.of(lowerTier));

            service.approveReturn(1L, 100L);

            verify(userTierRepository).findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any());
            verify(user).updateTier(lowerTier);
        }

        @Test
        @DisplayName("승인 시 Order를 비관적 잠금(findByIdWithLock)으로 획득한다")
        void approveReturn_locksOrder() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            // 관리자 호출이므로 findByIdWithLock (userId 없는 버전) 사용
            verify(orderRepository).findByIdWithLock(1L);
            verify(orderRepository, never()).findByIdAndUserIdWithLock(anyLong(), anyLong());
        }

        @Test
        @DisplayName("승인 시 락 순서가 Order → Product → User이다")
        void approveReturn_lockOrderIsConsistent() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 500, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            var inOrder = inOrder(orderRepository, productRepository, userRepository);
            inOrder.verify(orderRepository).findByIdWithLock(1L);
            inOrder.verify(productRepository).findByIdWithLock(10L);
            inOrder.verify(userRepository).findByIdWithLockAndTier(101L);
        }

        @Test
        @DisplayName("RETURN_REQUESTED가 아닌 상태(NORMAL)에서 승인 시 BusinessException 발생")
        void normalItem_approveReturn_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            // itemA는 기본 NORMAL 상태

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.approveReturn(1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS");

            verifyNoInteractions(productRepository, userRepository);
        }

        @Test
        @DisplayName("RETURN_REJECTED 상태에서 직접 승인 시 BusinessException 발생")
        void rejectedItem_approveReturn_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REJECTED);

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.approveReturn(1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS");

            verifyNoInteractions(productRepository, userRepository);
        }

        @Test
        @DisplayName("존재하지 않는 주문 시 ResourceNotFoundException 발생")
        void invalidOrderId_throwsException() {
            when(orderRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveReturn(999L, 100L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("존재하지 않는 주문상품 ID 시 ResourceNotFoundException 발생")
        void invalidOrderItemId_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.approveReturn(1L, 999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 관리자 반품 거절 — Step 2 신규
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("관리자 반품 거절 (Step 2 신규)")
    class RejectReturn {

        @Test
        @DisplayName("RETURN_REQUESTED 상태에서 거절 시 상태만 전이한다 (재고/환불 없음)")
        void rejectReturn_onlyTransitionsState() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            service.rejectReturn(1L, 100L, "상품 훼손 확인됨");

            // OrderItem.rejectReturn이 호출되어 거절 사유 기록 + 상태 전이
            verify(itemA).rejectReturn("상품 훼손 확인됨");

            // 재고/환불/포인트/등급 변경 없음
            verifyNoInteractions(productRepository);
            verifyNoInteractions(userRepository);
            verifyNoInteractions(pointHistoryRepository);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("거절 시 Order를 비관적 잠금으로 획득한다")
        void rejectReturn_locksOrder() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            service.rejectReturn(1L, 100L, "사유 불충분");

            verify(orderRepository).findByIdWithLock(1L);
        }

        @Test
        @DisplayName("거절 시 Order 환불 금액이 변경되지 않는다")
        void rejectReturn_doesNotUpdateOrderRefund() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            OrderItem itemA = order.getItems().get(0);

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            service.rejectReturn(1L, 100L, "검수 불합격");

            verify(order, never()).addRefundedAmount(any());
            verify(order, never()).addRefundedPoints(anyInt());
        }

        @Test
        @DisplayName("존재하지 않는 주문 시 ResourceNotFoundException 발생")
        void invalidOrderId_throwsException() {
            when(orderRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rejectReturn(999L, 100L, "사유"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("존재하지 않는 주문상품 ID 시 ResourceNotFoundException 발생")
        void invalidOrderItemId_throwsException() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.rejectReturn(1L, 999L, "사유"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 안전 상한 검증 (기존 테스트 유지)
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("안전 상한 — 초과 환불 방지")
    class SafetyCaps {

        @Test
        @DisplayName("포인트 환불 누계가 usedPoints를 초과하지 않는다")
        void pointRefund_doesNotExceedUsedPoints() {
            Order order = createTestOrder(OrderStatus.PAID);
            when(order.getRefundedPoints()).thenReturn(900);
            when(order.getUsedPoints()).thenReturn(1000);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 900, new BigDecimal("100000"));

            when(orderRepository.findByIdAndUserIdWithLock(1L, 101L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.partialCancel(101L, 1L, 100L, 1);

            ArgumentCaptor<Integer> pointsCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(user).addPoints(pointsCaptor.capture());
            assertThat(pointsCaptor.getValue()).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("[승인] 포인트 환불 누계가 usedPoints를 초과하지 않는다")
        void approveReturn_pointRefund_doesNotExceedUsedPoints() {
            Order order = createTestOrder(OrderStatus.DELIVERED);
            when(order.getRefundedPoints()).thenReturn(900);
            when(order.getUsedPoints()).thenReturn(1000);

            OrderItem itemA = order.getItems().get(0);
            when(itemA.getStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
            when(itemA.getPendingReturnQuantity()).thenReturn(1);

            Product product = createMockProduct(10L, 50);
            User user = createMockUser(101L, 900, new BigDecimal("100000"));

            when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(order));
            when(productRepository.findByIdWithLock(10L)).thenReturn(Optional.of(product));
            when(userRepository.findByIdWithLockAndTier(101L)).thenReturn(Optional.of(user));

            service.approveReturn(1L, 100L);

            ArgumentCaptor<Integer> pointsCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(user).addPoints(pointsCaptor.capture());
            assertThat(pointsCaptor.getValue()).isLessThanOrEqualTo(100);
        }
    }
}
