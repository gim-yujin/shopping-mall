package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.user.entity.User;
import com.shop.global.event.OrderCompletedEvent;
import com.shop.global.exception.BusinessException;
import com.shop.global.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderPostProcessor 단위 테스트.
 *
 * <p>주문 사후처리 로직의 각 분기를 격리 검증한다:
 * 재고 이력 저장, 쿠폰 처리, 포인트 차감, 장바구니 삭제, 이벤트 발행.</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderPostProcessorUnitTest {

    @Mock private ProductInventoryHistoryRepository inventoryHistoryRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private CartRepository cartRepository;
    @Mock private OutboxEventPublisher outboxEventPublisher;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    @Mock private Order savedOrder;
    @Mock private UserCoupon userCoupon;

    private OrderPostProcessor processor;
    private User user;

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 100L;
    private static final BigDecimal FINAL_AMOUNT = new BigDecimal("30000");

    @BeforeEach
    void setUp() {
        processor = new OrderPostProcessor(
                inventoryHistoryRepository, userCouponRepository, pointHistoryRepository,
                cartRepository, outboxEventPublisher, applicationEventPublisher
        );
        user = new User("testuser", "test@example.com", "hash", "테스트", "010-0000-0000");
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        ReflectionTestUtils.setField(user, "pointBalance", 1000);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────

    /** 재고 스냅샷 1개를 포함하는 StockDeductionResult */
    private OrderStockProcessor.StockDeductionResult createStockResult(Long productId) {
        List<OrderStockProcessor.InventorySnapshot> snapshots = List.of(
                new OrderStockProcessor.InventorySnapshot(productId, 2, 10, 8)
        );
        List<OrderStockProcessor.OrderLine> orderLines = List.of(
                new OrderStockProcessor.OrderLine(productId, "상품", 2,
                        new BigDecimal("10000"), new BigDecimal("20000"))
        );
        return new OrderStockProcessor.StockDeductionResult(
                new BigDecimal("20000"), BigDecimal.ZERO, orderLines, snapshots);
    }

    private Product createProduct(Long productId) {
        Product product = Product.create("상품_" + productId,
                mock(com.shop.domain.category.entity.Category.class),
                "설명", new BigDecimal("10000"), new BigDecimal("12000"), 10);
        ReflectionTestUtils.setField(product, "productId", productId);
        return product;
    }

    private Cart createCart(Long cartId, Long productId) {
        Product product = createProduct(productId);
        Cart cart = new Cart(USER_ID, product, 1);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    /** 기본 savedOrder stub 설정 */
    private void stubOrder() {
        when(savedOrder.getUserId()).thenReturn(USER_ID);
        when(savedOrder.getOrderId()).thenReturn(ORDER_ID);
        when(savedOrder.getFinalAmount()).thenReturn(FINAL_AMOUNT);
        lenient().when(savedOrder.getOrderNumber()).thenReturn("ORD-20260101-001");
    }

    // ── 항상 실행 검증 ───────────────────────────────────────

    @Nested
    @DisplayName("항상 실행되는 처리")
    class AlwaysExecuted {

        @Test
        @DisplayName("inventoryHistoryRepository.saveAll() 1회 호출됨")
        void savesInventoryHistory() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(inventoryHistoryRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("OrderCompletedEvent가 applicationEventPublisher를 통해 발행됨")
        void publishesOrderCompletedEvent() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            ArgumentCaptor<OrderCompletedEvent> captor =
                    ArgumentCaptor.forClass(OrderCompletedEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
            assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("publishStockChanged + publishOrderCreated 각각 1회 호출됨")
        void publishesOutboxEvents() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(outboxEventPublisher).publishStockChanged(anyList());
            verify(outboxEventPublisher).publishOrderCreated(eq(ORDER_ID), eq(USER_ID), eq(FINAL_AMOUNT));
        }
    }

    // ── 쿠폰 처리 ────────────────────────────────────────────

    @Nested
    @DisplayName("쿠폰 처리")
    class CouponHandling {

        @Test
        @DisplayName("쿠폰 없음 → userCouponRepository 미호출")
        void noCoupon_skipsCouponMarking() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(userCouponRepository, never()).markAsUsedIfUnused(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("쿠폰 있음, markAsUsedIfUnused=1 → 정상 처리")
        void validCoupon_marksAsUsed() {
            stubOrder();
            when(userCoupon.getUserCouponId()).thenReturn(50L);
            when(userCouponRepository.markAsUsedIfUnused(eq(50L), eq(ORDER_ID), any(LocalDateTime.class)))
                    .thenReturn(1);
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            assertThatCode(() ->
                    processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, userCoupon, 0))
                    .doesNotThrowAnyException();

            verify(userCouponRepository).markAsUsedIfUnused(eq(50L), eq(ORDER_ID), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("쿠폰 이미 사용됨 (markAsUsedIfUnused=0) → BusinessException(COUPON_ALREADY_USED)")
        void couponAlreadyUsed_throwsBusinessException() {
            when(savedOrder.getUserId()).thenReturn(USER_ID);
            when(savedOrder.getOrderId()).thenReturn(ORDER_ID);
            when(userCoupon.getUserCouponId()).thenReturn(50L);
            when(userCouponRepository.markAsUsedIfUnused(eq(50L), eq(ORDER_ID), any(LocalDateTime.class)))
                    .thenReturn(0);

            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            assertThatThrownBy(() ->
                    processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, userCoupon, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 사용된 쿠폰");
        }
    }

    // ── 포인트 처리 ──────────────────────────────────────────

    @Nested
    @DisplayName("포인트 처리")
    class PointHandling {

        @Test
        @DisplayName("usePoints=0 → pointHistoryRepository.save() 미호출")
        void usePointsZero_skipsPointHistory() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(pointHistoryRepository, never()).save(any(PointHistory.class));
        }

        @Test
        @DisplayName("usePoints>0 → PointHistory(USE) 저장, amount와 changeType 검증")
        void usePointsPositive_savesPointHistory() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 500);

            ArgumentCaptor<PointHistory> captor = ArgumentCaptor.forClass(PointHistory.class);
            verify(pointHistoryRepository).save(captor.capture());
            assertThat(captor.getValue().getChangeType()).isEqualTo(PointHistory.USE);
            assertThat(captor.getValue().getAmount()).isEqualTo(500);
        }
    }

    // ── 장바구니 삭제 ────────────────────────────────────────

    @Nested
    @DisplayName("장바구니 삭제")
    class CartDeletion {

        @Test
        @DisplayName("부분 주문 → deleteAllById(orderedCartIds), deleteByUserId 미호출")
        void partialOrder_deletesSpecificCartItems() {
            stubOrder();
            Cart cart = createCart(1L, 10L);
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(
                            new ArrayList<>(List.of(cart)), true);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(cartRepository).deleteAllById(List.of(1L));
            verify(cartRepository, never()).deleteByUserId(anyLong());
        }

        @Test
        @DisplayName("전체 주문 → deleteByUserId(userId), deleteAllById 미호출")
        void fullOrder_deletesAllUserCart() {
            stubOrder();
            OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
            OrderCartSelectionResolver.CartSelection cartSelection =
                    new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

            processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

            verify(cartRepository).deleteByUserId(USER_ID);
            verify(cartRepository, never()).deleteAllById(anyList());
        }
    }

    // ── totalSpent 업데이트 ─────────────────────────────────

    @Test
    @DisplayName("finalAmount만큼 user.totalSpent가 증가함")
    void addsUserTotalSpent() {
        stubOrder();
        OrderStockProcessor.StockDeductionResult stockResult = createStockResult(10L);
        OrderCartSelectionResolver.CartSelection cartSelection =
                new OrderCartSelectionResolver.CartSelection(new ArrayList<>(), false);

        processor.finalizeOrder(savedOrder, user, cartSelection, stockResult, null, 0);

        assertThat(user.getTotalSpent()).isEqualByComparingTo(FINAL_AMOUNT);
    }
}
