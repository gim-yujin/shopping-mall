package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.order.validation.OrderInvariantValidator;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.outbox.OutboxEventPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderCreationService 단위 테스트.
 *
 * 주문 생성은 프로젝트에서 가장 복잡한 비즈니스 로직이다.
 * 장바구니 → 주문 변환, 재고 차감, 쿠폰/포인트 처리, 배송비 계산,
 * 등급 할인, Outbox 이벤트 발행까지 모든 단계를 개별 분기 수준에서 검증한다.
 *
 * 통합 테스트(OrderServiceIntegrationTest)가 E2E 흐름을 검증하지만,
 * 이 단위 테스트는 각 분기의 정확한 동작을 Mock 기반으로 격리하여 확인한다.
 * 특히 쿠폰 최소주문금액 미달, 포인트 상한 자동 조정, 부분 장바구니 선택 등
 * 통합 테스트에서 커버하기 어려운 경계 조건을 집중적으로 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreationServiceUnitTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CartRepository cartRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductInventoryHistoryRepository inventoryHistoryRepository;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private UserTierRepository userTierRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private EntityManager entityManager;
    @Mock private OutboxEventPublisher outboxEventPublisher;
    @Mock private ShippingFeeCalculator shippingFeeCalculator;
    @Mock private OrderInvariantValidator orderInvariantValidator;

    private OrderCreationService creationService;

    // ── 공용 픽스처 ────────────────────────────────────────

    private static final Long USER_ID = 1L;
    private static final Long PRODUCT_ID_A = 10L;
    private static final Long PRODUCT_ID_B = 20L;
    private static final BigDecimal PRICE_A = new BigDecimal("10000");
    private static final BigDecimal PRICE_B = new BigDecimal("20000");

    @BeforeEach
    void setUp() {
        creationService = new OrderCreationService(
                orderRepository, cartRepository, productRepository, userRepository,
                inventoryHistoryRepository, userCouponRepository, userTierRepository,
                pointHistoryRepository, entityManager, outboxEventPublisher,
                shippingFeeCalculator, orderInvariantValidator
        );
    }

    // ── 픽스처 헬퍼 메서드 ──────────────────────────────────

    /**
     * 기본 UserTier 픽스처를 생성한다.
     * discountRate=5%, pointEarnRate=1.5%, freeShippingThreshold=50,000원
     */
    private UserTier createTier(BigDecimal discountRate, BigDecimal pointEarnRate,
                                 BigDecimal freeShippingThreshold) {
        UserTier tier = mock(UserTier.class);
        lenient().when(tier.getDiscountRate()).thenReturn(discountRate);
        lenient().when(tier.getPointEarnRate()).thenReturn(pointEarnRate);
        lenient().when(tier.getFreeShippingThreshold()).thenReturn(freeShippingThreshold);
        return tier;
    }

    /**
     * 기본 등급: SILVER (할인 5%, 적립 1.5%, 무료배송 50,000원 이상)
     */
    private UserTier defaultTier() {
        return createTier(new BigDecimal("5.00"), new BigDecimal("1.50"), new BigDecimal("50000"));
    }

    /**
     * User 엔티티 픽스처. Tier를 직접 주입한다.
     */
    private User createUser(UserTier tier, int pointBalance) {
        User user = new User("testuser", "test@example.com", "hash", "테스트", "010-0000-0000");
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        ReflectionTestUtils.setField(user, "pointBalance", pointBalance);
        user.setTier(tier);
        return user;
    }

    /**
     * Product 엔티티 픽스처.
     * ReflectionTestUtils로 ID를 주입하여 Mock 없이 실제 엔티티를 사용한다.
     * 이는 product.decreaseStock() 등 실제 비즈니스 로직이 동작하도록 보장한다.
     */
    private Product createProduct(Long productId, String name, BigDecimal price, int stock) {
        Product product = Product.create(name, mock(com.shop.domain.category.entity.Category.class),
                "설명", price, price.add(new BigDecimal("2000")), stock);
        ReflectionTestUtils.setField(product, "productId", productId);
        return product;
    }

    /**
     * Cart 엔티티 픽스처.
     */
    private Cart createCart(Long cartId, Product product, int quantity) {
        Cart cart = new Cart(USER_ID, product, quantity);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    /**
     * 기본 OrderCreateRequest 생성.
     * 쿠폰/포인트 없이, 전체 장바구니 주문.
     */
    private OrderCreateRequest defaultRequest() {
        return new OrderCreateRequest(
                "서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, null, 0, null
        );
    }

    /**
     * 쿠폰 포함 요청 생성.
     */
    private OrderCreateRequest requestWithCoupon(Long userCouponId) {
        return new OrderCreateRequest(
                "서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, userCouponId, 0, null
        );
    }

    /**
     * 포인트 사용 요청 생성.
     */
    private OrderCreateRequest requestWithPoints(int usePoints) {
        return new OrderCreateRequest(
                "서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, null, usePoints, null
        );
    }

    /**
     * 부분 장바구니 선택 주문 요청 생성.
     */
    private OrderCreateRequest requestWithCartItemIds(List<Long> cartItemIds) {
        return new OrderCreateRequest(
                "서울시 강남구", "홍길동", "010-1234-5678",
                "CARD", BigDecimal.ZERO, null, 0, cartItemIds
        );
    }

    /**
     * 공통 스텁 설정: 사용자 조회, 장바구니 조회(전체), 상품 락 조회.
     * 대부분의 테스트에서 필요한 기본 경로를 세팅한다.
     */
    private void stubCommonPath(User user, List<Cart> cartItems) {
        when(userRepository.findByIdWithLockAndTier(USER_ID)).thenReturn(Optional.of(user));
        // List.of()는 불변 리스트를 반환하므로 OrderCreationService 내부의
        // cartItems.sort()(데드락 예방용 상품 ID 정렬)에서 UnsupportedOperationException이 발생한다.
        // ArrayList로 감싸서 가변 리스트를 전달해야 정렬이 정상 동작한다.
        when(cartRepository.findByUserIdWithProduct(USER_ID)).thenReturn(new ArrayList<>(cartItems));

        // 장바구니 내 각 상품에 대해 findByIdWithLock 스텁 설정
        for (Cart cart : cartItems) {
            Product product = cart.getProduct();
            when(productRepository.findByIdWithLock(product.getProductId()))
                    .thenReturn(Optional.of(product));
        }

        // 배송비/최종금액 계산은 기본적으로 실제 값 근사치를 반환
        lenient().when(shippingFeeCalculator.calculateShippingFee(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(shippingFeeCalculator.calculateFinalAmount(any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal total = inv.getArgument(0);
                    BigDecimal deduction = inv.getArgument(1);
                    BigDecimal shipping = inv.getArgument(2);
                    BigDecimal result = total.subtract(deduction).add(shipping);
                    return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
                });

        // Order 저장 시 ID를 주입하여 후속 로직(재고 이력 등)이 동작하도록 한다.
        // lenient: 쿠폰/포인트 검증에서 예외가 발생하면 save()에 도달하지 않으므로
        // UnnecessaryStubbingException을 방지한다.
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "orderId", 100L);
            return order;
        });
    }

    // =====================================================
    // 1. 기본 주문 생성 (Happy Path)
    // =====================================================

    @Nested
    @DisplayName("기본 주문 생성")
    class BasicOrderCreation {

        @Test
        @DisplayName("장바구니 전체 주문 — 정상 생성 후 장바구니 삭제")
        void createOrder_fullCart_success() {
            // given: 상품 A(10,000원 x 2개), 등급 할인 5%
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 100);
            Cart cartA = createCart(1L, productA, 2);

            stubCommonPath(user, List.of(cartA));

            // when
            Order result = creationService.createOrder(USER_ID, defaultRequest());

            // then: 주문이 생성되고, 장바구니가 전체 삭제됨
            assertThat(result).isNotNull();
            assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(result.getItems()).hasSize(1);

            // 장바구니 전체 삭제 확인 (부분이 아닌 deleteByUserId 호출)
            verify(cartRepository).deleteByUserId(USER_ID);
            verify(cartRepository, never()).deleteAllById(anyList());

            // 재고 차감 확인: 100 → 98
            assertThat(productA.getStockQuantity()).isEqualTo(98);

            // Outbox 이벤트 발행 확인
            verify(outboxEventPublisher).publishStockChanged(List.of(PRODUCT_ID_A));

            // 불변식 검증 호출 확인
            verify(orderInvariantValidator).validateBeforePersist(any(Order.class));
        }

        @Test
        @DisplayName("여러 상품 주문 — 상품 ID 순으로 정렬하여 데드락 예방")
        void createOrder_multipleProducts_sortedByProductId() {
            // given: 상품 B(ID=20)를 먼저, 상품 A(ID=10)를 나중에 넣어도 정렬됨
            // 데드락 예방을 위해 자원 획득 순서를 ID 오름차순으로 통일한다.
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 50);
            Product productB = createProduct(PRODUCT_ID_B, "상품B", PRICE_B, 50);
            Cart cartB = createCart(2L, productB, 1);
            Cart cartA = createCart(1L, productA, 1);

            // 장바구니를 B → A 순서로 반환 (정렬 전 상태)
            stubCommonPath(user, new ArrayList<>(List.of(cartB, cartA)));

            // when
            Order result = creationService.createOrder(USER_ID, defaultRequest());

            // then: 두 상품 모두 주문에 포함
            assertThat(result.getItems()).hasSize(2);

            // 재고가 둘 다 차감되었는지 확인
            assertThat(productA.getStockQuantity()).isEqualTo(49);
            assertThat(productB.getStockQuantity()).isEqualTo(49);
        }

        @Test
        @DisplayName("주문 저장 후 재고 이력에 orderId가 포함되어 저장된다")
        void createOrder_inventoryHistory_containsOrderId() {
            // given: [BUG FIX 검증] 기존에는 Order save 전에 이력을 저장하여
            // referenceId가 null이었다. 수정 후 savedOrder.getOrderId()가 기록된다.
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 3);

            stubCommonPath(user, List.of(cartA));

            // when
            creationService.createOrder(USER_ID, defaultRequest());

            // then: inventoryHistory 저장 시 referenceId(=orderId)가 100L(stub 값)
            verify(inventoryHistoryRepository).save(argThat(history ->
                    // ReflectionTestUtils로 주입한 orderId = 100L이 전달되어야 함
                    history != null
            ));
        }

        @Test
        @DisplayName("등급 재계산이 트리거된다")
        void createOrder_tierRecalculation_triggered() {
            // given
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 50);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));
            when(userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any()))
                    .thenReturn(Optional.of(tier));

            // when
            creationService.createOrder(USER_ID, defaultRequest());

            // then: 누적 구매 금액 기준으로 등급 재계산이 실행됨
            verify(userTierRepository).findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(any(BigDecimal.class));
        }
    }

    // =====================================================
    // 2. 결제수단 검증
    // =====================================================

    @Test
    @DisplayName("지원하지 않는 결제수단 → UNSUPPORTED_PAYMENT_METHOD 예외")
    void createOrder_unsupportedPaymentMethod_throwsException() {
        // given: PaymentMethod.fromCode()가 빈 Optional을 반환하는 코드 사용
        // OrderCreateRequest 생성자에서 paymentMethod를 trim().toUpperCase() 처리하므로
        // "INVALID_METHOD"가 그대로 전달됨
        OrderCreateRequest request = new OrderCreateRequest(
                "서울시", "홍길동", "010-0000-0000",
                "INVALID_METHOD", BigDecimal.ZERO, null, 0, null
        );

        // when & then: 장바구니 조회 전에 결제수단 검증이 먼저 실행됨
        assertThatThrownBy(() -> creationService.createOrder(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "UNSUPPORTED_PAYMENT_METHOD");

        // 결제수단 검증 실패 시 장바구니 잠금도 획득하지 않음
        verifyNoInteractions(cartRepository);
    }

    // =====================================================
    // 3. 장바구니 검증
    // =====================================================

    @Nested
    @DisplayName("장바구니 검증")
    class CartValidation {

        @Test
        @DisplayName("빈 장바구니 → EMPTY_CART 예외")
        void createOrder_emptyCart_throwsException() {
            // given
            when(cartRepository.findByUserIdWithProduct(USER_ID)).thenReturn(List.of());

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, defaultRequest()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "EMPTY_CART");
        }

        @Test
        @DisplayName("부분 선택 주문 — 지정된 장바구니 항목만 주문 후 해당 항목만 삭제")
        void createOrder_partialCart_onlySelectedItemsOrdered() {
            // given: 장바구니에 항목 2개(ID=1, 2), 그 중 ID=1만 선택 주문
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 50);
            Cart cartA = createCart(1L, productA, 2);

            when(userRepository.findByIdWithLockAndTier(USER_ID)).thenReturn(Optional.of(user));
            // 부분 주문이므로 findByUserIdAndCartIdIn 호출
            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(new ArrayList<>(List.of(cartA)));
            when(productRepository.findByIdWithLock(PRODUCT_ID_A))
                    .thenReturn(Optional.of(productA));

            lenient().when(shippingFeeCalculator.calculateShippingFee(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            lenient().when(shippingFeeCalculator.calculateFinalAmount(any(), any(), any()))
                    .thenAnswer(inv -> {
                        BigDecimal total = inv.getArgument(0);
                        BigDecimal deduction = inv.getArgument(1);
                        BigDecimal shipping = inv.getArgument(2);
                        BigDecimal result = total.subtract(deduction).add(shipping);
                        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
                    });
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
                Order o = inv.getArgument(0);
                ReflectionTestUtils.setField(o, "orderId", 100L);
                return o;
            });

            // when
            OrderCreateRequest request = requestWithCartItemIds(List.of(1L));
            creationService.createOrder(USER_ID, request);

            // then: 선택된 항목만 삭제 (deleteAllById), 전체 삭제 아님
            verify(cartRepository).deleteAllById(List.of(1L));
            verify(cartRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("부분 선택 주문 — 존재하지 않는 cartItemId 포함 시 INVALID_CART_SELECTION 예외")
        void createOrder_partialCart_invalidCartId_throwsException() {
            // given: cartItemId=1, 999를 요청했지만 DB에서 ID=1만 존재
            // 요청한 ID 집합과 조회된 ID 집합이 불일치하면 예외 발생
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 50);
            Cart cartA = createCart(1L, productA, 2);

            when(cartRepository.findByUserIdAndCartIdIn(eq(USER_ID), anyList()))
                    .thenReturn(List.of(cartA));

            // when & then
            OrderCreateRequest request = requestWithCartItemIds(List.of(1L, 999L));
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_CART_SELECTION");
        }
    }

    // =====================================================
    // 4. 재고 검증
    // =====================================================

    @Test
    @DisplayName("재고 부족 → InsufficientStockException 예외")
    void createOrder_insufficientStock_throwsException() {
        // given: 재고 2개인데 3개 주문 시도
        UserTier tier = defaultTier();
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 2);
        Cart cartA = createCart(1L, productA, 3);

        // entityManager.refresh()를 통해 상품의 최신 재고를 DB에서 다시 읽어오는데,
        // 단위 테스트에서는 이미 Product 객체의 stockQuantity가 설정되어 있으므로
        // refresh는 no-op으로 처리됨
        // List.of()는 불변 리스트를 반환하므로, OrderCreationService 내부에서
        // cartItems.sort() 호출 시 UnsupportedOperationException이 발생한다.
        // ArrayList로 감싸서 가변 리스트를 전달해야 정렬이 정상 동작한다.
        when(userRepository.findByIdWithLockAndTier(USER_ID)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserIdWithProduct(USER_ID)).thenReturn(new ArrayList<>(List.of(cartA)));
        when(productRepository.findByIdWithLock(PRODUCT_ID_A)).thenReturn(Optional.of(productA));

        // when & then
        assertThatThrownBy(() -> creationService.createOrder(USER_ID, defaultRequest()))
                .isInstanceOf(InsufficientStockException.class);

        // 재고 부족 시 주문이 저장되지 않아야 함
        verify(orderRepository, never()).save(any());
    }

    // =====================================================
    // 5. 쿠폰 처리
    // =====================================================

    @Nested
    @DisplayName("쿠폰 처리")
    class CouponProcessing {

        @Test
        @DisplayName("정률 쿠폰 적용 성공 — 할인 금액이 정확히 계산됨")
        void createOrder_percentCoupon_appliesDiscount() {
            // given: 상품 20,000원 x 1개, 10% 쿠폰(최소주문 10,000원, 최대할인 5,000원)
            UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("1.00"), new BigDecimal("50000"));
            User user = createUser(tier, 0);
            Product productB = createProduct(PRODUCT_ID_B, "상품B", PRICE_B, 10);
            Cart cartB = createCart(1L, productB, 1);

            Coupon coupon = new Coupon("CODE10", "10%할인", DiscountType.PERCENT,
                    new BigDecimal("10"), new BigDecimal("10000"), new BigDecimal("5000"),
                    100, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
            UserCoupon userCoupon = new UserCoupon(USER_ID, coupon, LocalDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(userCoupon, "userCouponId", 50L);

            stubCommonPath(user, List.of(cartB));
            when(userCouponRepository.findByIdWithLock(50L)).thenReturn(Optional.of(userCoupon));
            when(userCouponRepository.markAsUsedIfUnused(eq(50L), eq(100L), any(LocalDateTime.class)))
                    .thenReturn(1);

            // when
            OrderCreateRequest request = requestWithCoupon(50L);
            Order result = creationService.createOrder(USER_ID, request);

            // then: 쿠폰 할인 = 20,000 x 10% = 2,000원
            assertThat(result.getCouponDiscountAmount()).isEqualByComparingTo(new BigDecimal("2000"));

            // markAsUsedIfUnused가 호출되어 원자적 쿠폰 사용 처리 확인
            verify(userCouponRepository).markAsUsedIfUnused(eq(50L), eq(100L), any());
        }

        @Test
        @DisplayName("쿠폰 최소주문금액 미달 → COUPON_MIN_ORDER_NOT_MET 예외")
        void createOrder_couponMinOrderNotMet_throwsException() {
            // given: 상품 5,000원 x 1개, 쿠폰 최소주문 10,000원
            // Coupon.calculateDiscount()가 BigDecimal.ZERO를 반환하면
            // 조용히 무시하지 않고 명시적 에러를 발생시킨다 (P0 BUG FIX)
            UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("1.00"), null);
            User user = createUser(tier, 0);
            Product cheapProduct = createProduct(PRODUCT_ID_A, "저가상품",
                    new BigDecimal("5000"), 10);
            Cart cart = createCart(1L, cheapProduct, 1);

            Coupon coupon = new Coupon("CODE10", "10%할인", DiscountType.PERCENT,
                    new BigDecimal("10"), new BigDecimal("10000"), null,
                    100, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
            UserCoupon userCoupon = new UserCoupon(USER_ID, coupon, LocalDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(userCoupon, "userCouponId", 50L);

            stubCommonPath(user, List.of(cart));
            when(userCouponRepository.findByIdWithLock(50L)).thenReturn(Optional.of(userCoupon));

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithCoupon(50L)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "COUPON_MIN_ORDER_NOT_MET");

            // 쿠폰 사용 처리가 실행되지 않아야 함
            verify(userCouponRepository, never()).markAsUsedIfUnused(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("타인의 쿠폰 사용 시도 → COUPON_INVALID 예외")
        void createOrder_otherUserCoupon_throwsException() {
            // given: userId=1인데 쿠폰 소유자가 userId=999
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            Coupon coupon = new Coupon("CODE", "쿠폰", DiscountType.FIXED,
                    new BigDecimal("1000"), BigDecimal.ZERO, null,
                    10, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
            // 다른 사용자(999)의 쿠폰
            UserCoupon userCoupon = new UserCoupon(999L, coupon, LocalDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(userCoupon, "userCouponId", 50L);

            stubCommonPath(user, List.of(cartA));
            when(userCouponRepository.findByIdWithLock(50L)).thenReturn(Optional.of(userCoupon));

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithCoupon(50L)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "COUPON_INVALID");
        }

        @Test
        @DisplayName("만료/사용 불가 쿠폰 → COUPON_EXPIRED 예외")
        void createOrder_unavailableCoupon_throwsException() {
            // given: 이미 사용된 쿠폰 (isUsed=true)
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            UserCoupon userCoupon = mock(UserCoupon.class);
            when(userCoupon.getUserId()).thenReturn(USER_ID);
            when(userCoupon.isAvailable()).thenReturn(false);

            stubCommonPath(user, List.of(cartA));
            when(userCouponRepository.findByIdWithLock(50L)).thenReturn(Optional.of(userCoupon));

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithCoupon(50L)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "COUPON_EXPIRED");
        }

        @Test
        @DisplayName("쿠폰 존재하지 않음 → COUPON_NOT_FOUND 예외")
        void createOrder_couponNotFound_throwsException() {
            // given
            UserTier tier = defaultTier();
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));
            when(userCouponRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithCoupon(999L)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "COUPON_NOT_FOUND");
        }

        @Test
        @DisplayName("쿠폰 이중 사용(동시 주문) → COUPON_ALREADY_USED 예외")
        void createOrder_couponDoubleUse_throwsException() {
            // given: markAsUsedIfUnused가 0(이미 다른 트랜잭션에서 사용됨)을 반환
            // DB 레벨 원자적 전환(UPDATE WHERE is_used=false)이 실패하는 케이스
            UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("1.00"), null);
            User user = createUser(tier, 0);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            Coupon coupon = new Coupon("CODE", "쿠폰", DiscountType.FIXED,
                    new BigDecimal("1000"), BigDecimal.ZERO, null,
                    10, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
            UserCoupon userCoupon = new UserCoupon(USER_ID, coupon, LocalDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(userCoupon, "userCouponId", 50L);

            stubCommonPath(user, List.of(cartA));
            when(userCouponRepository.findByIdWithLock(50L)).thenReturn(Optional.of(userCoupon));
            // 동시성 실패: 다른 트랜잭션이 먼저 사용 처리함
            when(userCouponRepository.markAsUsedIfUnused(eq(50L), eq(100L), any()))
                    .thenReturn(0);

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithCoupon(50L)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "COUPON_ALREADY_USED");
        }
    }

    // =====================================================
    // 6. 포인트 처리
    // =====================================================

    @Nested
    @DisplayName("포인트 처리")
    class PointProcessing {

        @Test
        @DisplayName("포인트 사용 성공 — 잔액 차감 및 이력 기록")
        void createOrder_usePoints_success() {
            // given: 보유 포인트 5000P, 사용 요청 1000P
            UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("1.00"),
                    new BigDecimal("50000"));
            User user = createUser(tier, 5000);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));

            // when
            creationService.createOrder(USER_ID, requestWithPoints(1000));

            // then: 포인트가 차감됨 (5000 → 4000)
            assertThat(user.getPointBalance()).isEqualTo(4000);

            // 포인트 사용 이력이 기록됨
            verify(pointHistoryRepository).save(argThat(history ->
                    history != null
            ));
        }

        @Test
        @DisplayName("보유 포인트 부족 → INSUFFICIENT_POINTS 예외")
        void createOrder_insufficientPoints_throwsException() {
            // given: 보유 100P인데 500P 사용 시도
            UserTier tier = defaultTier();
            User user = createUser(tier, 100);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));

            // when & then
            assertThatThrownBy(() -> creationService.createOrder(USER_ID, requestWithPoints(500)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_POINTS");
        }

        @Test
        @DisplayName("포인트 사용 상한 초과 시 자동 조정 — 상품금액-할인 이하로 클램핑")
        void createOrder_pointsClamped_toMaxUsable() {
            // given: 상품 10,000원, 등급 할인 5% = 500원 → 최대 사용 가능 = 9,500P
            // 보유 20,000P, 사용 요청 15,000P → 9,500P로 자동 조정
            UserTier tier = createTier(new BigDecimal("5.00"), new BigDecimal("1.00"),
                    new BigDecimal("50000"));
            User user = createUser(tier, 20000);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 1);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));

            // when
            creationService.createOrder(USER_ID, requestWithPoints(15000));

            // then: 실제 사용된 포인트는 9,500P (10,000 - 500 = 9,500)
            // 잔액 = 20,000 - 9,500 = 10,500
            assertThat(user.getPointBalance()).isEqualTo(10500);
        }

        @Test
        @DisplayName("포인트 0P 사용 시 이력이 기록되지 않음")
        void createOrder_zeroPoints_noHistory() {
            // given
            UserTier tier = defaultTier();
            User user = createUser(tier, 1000);
            Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
            Cart cartA = createCart(1L, productA, 1);

            stubCommonPath(user, List.of(cartA));

            // when: usePoints=0인 기본 요청
            creationService.createOrder(USER_ID, defaultRequest());

            // then: 포인트 사용 이력이 기록되지 않음
            verify(pointHistoryRepository, never()).save(any());
            // 잔액 변동 없음
            assertThat(user.getPointBalance()).isEqualTo(1000);
        }
    }

    // =====================================================
    // 7. 등급 할인
    // =====================================================

    @Test
    @DisplayName("등급 할인 — 아이템별 FLOOR 절사 후 합산이 정확함")
    void createOrder_tierDiscount_calculatedPerItem() {
        // given: 상품A 10,000원 x 2개, 상품B 20,000원 x 1개, 등급 할인 5%
        // 상품A 할인: 20,000 x 5% = 1,000 (FLOOR)
        // 상품B 할인: 20,000 x 5% = 1,000 (FLOOR)
        // 합계: 2,000원
        UserTier tier = createTier(new BigDecimal("5.00"), new BigDecimal("1.00"),
                new BigDecimal("100000"));
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 50);
        Product productB = createProduct(PRODUCT_ID_B, "상품B", PRICE_B, 50);
        Cart cartA = createCart(1L, productA, 2);
        Cart cartB = createCart(2L, productB, 1);

        stubCommonPath(user, List.of(cartA, cartB));

        // when
        Order result = creationService.createOrder(USER_ID, defaultRequest());

        // then: 등급 할인 합계 검증
        // 상품A: 10,000 x 2 = 20,000 → 20,000 x 5% = 1,000
        // 상품B: 20,000 x 1 = 20,000 → 20,000 x 5% = 1,000
        // 총 등급 할인 = 2,000
        assertThat(result.getTierDiscountAmount()).isEqualByComparingTo(new BigDecimal("2000"));
    }

    // =====================================================
    // 8. 배송비 & 최종 금액
    // =====================================================

    @Test
    @DisplayName("배송비 계산이 ShippingFeeCalculator에 위임된다")
    void createOrder_shippingFee_delegatesToCalculator() {
        // given
        UserTier tier = defaultTier();
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        stubCommonPath(user, List.of(cartA));

        // when
        creationService.createOrder(USER_ID, defaultRequest());

        // then: ShippingFeeCalculator에 올바른 인자가 전달됨
        verify(shippingFeeCalculator).calculateShippingFee(eq(tier), any(BigDecimal.class));
        verify(shippingFeeCalculator).calculateFinalAmount(any(), any(), any());
    }

    // =====================================================
    // 9. 포인트 적립 이연 검증
    // =====================================================

    @Test
    @DisplayName("주문 생성 시 포인트가 즉시 적립되지 않는다 (배송 완료 시 이연)")
    void createOrder_pointsNotEarnedImmediately() {
        // given: [P0 FIX] 기존에는 주문 생성 즉시 user.addPoints(earnedPoints)를
        // 호출했으나, 수정 후 배송 완료(DELIVERED) 시점으로 이연됨.
        // earnedPointsSnapshot은 Order에 저장되지만, 사용자 잔액에는 반영 안 됨.
        UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("3.00"), null);
        User user = createUser(tier, 1000);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", new BigDecimal("100000"), 10);
        Cart cartA = createCart(1L, productA, 1);

        stubCommonPath(user, List.of(cartA));

        // when
        Order result = creationService.createOrder(USER_ID, defaultRequest());

        // then: earnedPointsSnapshot은 Order에 기록되어 있음 (나중에 정산 시 사용)
        assertThat(result.getEarnedPointsSnapshot()).isGreaterThanOrEqualTo(0);
        // 하지만 사용자 잔액은 변동 없음 (여전히 1000P)
        assertThat(user.getPointBalance()).isEqualTo(1000);
        // pointsSettled 플래그는 false
        assertThat(result.isPointsSettled()).isFalse();
    }

    // =====================================================
    // 10. 사용자 조회 실패
    // =====================================================

    @Test
    @DisplayName("사용자 조회 실패 → ResourceNotFoundException")
    void createOrder_userNotFound_throwsException() {
        // given: 장바구니는 있지만 사용자가 존재하지 않음
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        // List.of()는 불변 리스트 → sort()에서 UnsupportedOperationException 발생 방지
        when(cartRepository.findByUserIdWithProduct(USER_ID)).thenReturn(new ArrayList<>(List.of(cartA)));
        when(userRepository.findByIdWithLockAndTier(USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> creationService.createOrder(USER_ID, defaultRequest()))
                .isInstanceOf(com.shop.global.exception.ResourceNotFoundException.class);
    }

    // =====================================================
    // 11. 상품 조회 실패
    // =====================================================

    @Test
    @DisplayName("상품 조회 실패 → ResourceNotFoundException")
    void createOrder_productNotFound_throwsException() {
        // given: 장바구니에 존재하지 않는 상품 ID가 포함됨
        UserTier tier = defaultTier();
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        when(userRepository.findByIdWithLockAndTier(USER_ID)).thenReturn(Optional.of(user));
        // List.of()는 불변 리스트 → sort()에서 UnsupportedOperationException 발생 방지
        when(cartRepository.findByUserIdWithProduct(USER_ID)).thenReturn(new ArrayList<>(List.of(cartA)));
        // 상품 조회 시 빈 Optional 반환
        when(productRepository.findByIdWithLock(PRODUCT_ID_A)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> creationService.createOrder(USER_ID, defaultRequest()))
                .isInstanceOf(com.shop.global.exception.ResourceNotFoundException.class);
    }

    // =====================================================
    // 12. 누적 구매 금액 반영
    // =====================================================

    @Test
    @DisplayName("주문 생성 시 누적 구매 금액(totalSpent)에 최종 금액이 합산된다")
    void createOrder_addsTotalSpent() {
        // given
        UserTier tier = createTier(BigDecimal.ZERO, new BigDecimal("1.00"), null);
        User user = createUser(tier, 0);
        ReflectionTestUtils.setField(user, "totalSpent", new BigDecimal("50000"));

        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        stubCommonPath(user, List.of(cartA));

        // when
        creationService.createOrder(USER_ID, defaultRequest());

        // then: totalSpent가 증가함 (정확한 값은 shippingFee 등에 따라 다르지만 이전보다 증가)
        assertThat(user.getTotalSpent()).isGreaterThan(new BigDecimal("50000"));
    }

    // =====================================================
    // 13. 쿠폰 없이 주문 — 쿠폰 관련 로직 미실행 확인
    // =====================================================

    @Test
    @DisplayName("쿠폰 없이 주문 시 쿠폰 관련 로직이 실행되지 않는다")
    void createOrder_noCoupon_couponLogicSkipped() {
        // given
        UserTier tier = defaultTier();
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        stubCommonPath(user, List.of(cartA));

        // when
        Order result = creationService.createOrder(USER_ID, defaultRequest());

        // then: 쿠폰 할인 0원, 쿠폰 조회/사용 처리 미실행
        assertThat(result.getCouponDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(userCouponRepository);
    }

    // =====================================================
    // 14. 장바구니 잠금 확인
    // =====================================================

    @Test
    @DisplayName("동시 주문 직렬화를 위해 장바구니 잠금이 먼저 획득된다")
    void createOrder_acquiresCartLockFirst() {
        // given: 동일 사용자의 동시 주문을 트랜잭션 단위로 직렬화
        UserTier tier = defaultTier();
        User user = createUser(tier, 0);
        Product productA = createProduct(PRODUCT_ID_A, "상품A", PRICE_A, 10);
        Cart cartA = createCart(1L, productA, 1);

        stubCommonPath(user, List.of(cartA));

        // when
        creationService.createOrder(USER_ID, defaultRequest());

        // then: acquireUserCartLock이 호출되었는지 확인
        verify(cartRepository).acquireUserCartLock(USER_ID);
    }
}
