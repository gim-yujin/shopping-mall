package com.shop.domain.order.controller;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.entity.PaymentMethod;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.entity.Product;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OrderController 단위 테스트.
 *
 * <p>standaloneSetup 방식으로 Spring 컨텍스트 없이 빠르게 실행한다.
 * SecurityContextHolder에 인증 정보를 직접 설정하여
 * {@link com.shop.global.security.SecurityUtil#getCurrentUserId()}가
 * 올바른 userId(1L)를 반환하도록 구성한다.</p>
 *
 * <p>커버리지 목표: 12% → 70%+ (7개 엔드포인트 × 정상/실패 시나리오)</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerUnitTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 100L;

    @Mock
    private OrderService orderService;
    @Mock
    private CartService cartService;
    @Mock
    private UserService userService;
    @Mock
    private CouponService couponService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(
                orderService, cartService, userService, couponService);

        // PaymentMethodValidator가 @ValidPaymentMethod 어노테이션을 처리하려면
        // LocalValidatorFactoryBean이 standaloneSetup에 등록되어야 한다.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        // SecurityUtil.getCurrentUserId()가 USER_ID를 반환하도록 SecurityContext 설정.
        // standaloneSetup은 Spring Security 필터를 거치지 않으므로
        // SecurityContextHolder에 직접 Authentication을 주입한다.
        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        // 다른 테스트에 인증 상태가 누출되지 않도록 반드시 정리한다.
        SecurityContextHolder.clearContext();
    }

    // ── 테스트 픽스처 생성 헬퍼 ──────────────────────────────────

    /**
     * UserTier 엔티티를 리플렉션으로 생성한다.
     * UserTier는 protected 기본 생성자만 있고, @GeneratedValue PK를 사용하므로
     * 리플렉션으로 인스턴스를 생성한 뒤 ReflectionTestUtils로 필드를 설정해야 한다.
     */
    private UserTier createUserTier() {
        UserTier tier;
        try {
            var constructor = UserTier.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            tier = constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("UserTier 인스턴스 생성 실패", e);
        }
        ReflectionTestUtils.setField(tier, "tierName", "BASIC");
        ReflectionTestUtils.setField(tier, "tierLevel", 1);
        ReflectionTestUtils.setField(tier, "minSpent", BigDecimal.ZERO);
        ReflectionTestUtils.setField(tier, "discountRate", BigDecimal.ZERO);
        ReflectionTestUtils.setField(tier, "pointEarnRate", new BigDecimal("0.01"));
        ReflectionTestUtils.setField(tier, "freeShippingThreshold", new BigDecimal("50000"));
        return tier;
    }

    /**
     * 테스트용 User 엔티티를 생성하고, tier와 userId를 설정한다.
     * User 생성자는 tier를 받지 않으므로 별도로 setTier를 호출해야 한다.
     */
    private User createUser() {
        User user = new User("tester", "tester@example.com", "encoded", "테스터", "010-1111-2222");
        user.setTier(createUserTier());
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        return user;
    }

    /**
     * 장바구니 항목 1건을 생성한다.
     * Cart 엔티티는 Product를 참조하므로, Product 목 객체를 함께 설정한다.
     */
    private Cart createCartItem(Long cartId) {
        Product product = mock(Product.class);
        lenient().when(product.getPrice()).thenReturn(new BigDecimal("30000"));
        lenient().when(product.getProductName()).thenReturn("테스트 상품");

        Cart cart = new Cart(USER_ID, product, 2);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    /**
     * 테스트용 Order 엔티티를 생성한다.
     * orderId는 @GeneratedValue이므로 리플렉션으로 설정한다.
     */
    private Order createOrder() {
        Order order = new Order(
                "ORD-TEST-001", USER_ID,
                new BigDecimal("60000"),   // totalAmount
                BigDecimal.ZERO,           // discountAmount
                BigDecimal.ZERO,           // tierDiscountAmount
                BigDecimal.ZERO,           // couponDiscountAmount
                new BigDecimal("3000"),    // shippingFee
                new BigDecimal("63000"),   // finalAmount
                new BigDecimal("0.01"),    // pointEarnRateSnapshot
                630,                       // earnedPointsSnapshot
                0,                         // usedPoints
                "CARD",                    // paymentMethod
                "서울시 강남구",              // shippingAddress
                "홍길동",                    // recipientName
                "010-1234-5678"            // recipientPhone
        );
        ReflectionTestUtils.setField(order, "orderId", ORDER_ID);
        return order;
    }

    /**
     * 체크아웃 페이지에서 쿠폰 드롭다운에 표시할 UserCoupon 목록을 생성한다.
     * buildCouponDisplayNames 메서드가 정률/정액 할인을 올바르게 포맷하는지 검증하기 위해
     * PERCENT 타입과 FIXED 타입 쿠폰을 각각 포함한다.
     */
    private List<UserCoupon> createAvailableCoupons() {
        Coupon percentCoupon = new Coupon(
                "SAVE10", "10% 할인", DiscountType.PERCENT,
                new BigDecimal("10"), new BigDecimal("30000"), new BigDecimal("5000"),
                100, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30)
        );
        UserCoupon uc1 = new UserCoupon(USER_ID, percentCoupon, LocalDateTime.now().plusDays(30));
        ReflectionTestUtils.setField(uc1, "userCouponId", 10L);

        Coupon fixedCoupon = new Coupon(
                "FLAT3000", "3,000원 할인", DiscountType.FIXED,
                new BigDecimal("3000"), new BigDecimal("20000"), null,
                50, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30)
        );
        UserCoupon uc2 = new UserCoupon(USER_ID, fixedCoupon, LocalDateTime.now().plusDays(30));
        ReflectionTestUtils.setField(uc2, "userCouponId", 11L);

        return List.of(uc1, uc2);
    }

    // ── GET /orders/checkout ────────────────────────────────────

    @Nested
    @DisplayName("GET /orders/checkout — 주문/결제 페이지")
    class CheckoutPageTests {

        @Test
        @DisplayName("장바구니에 상품이 있으면 checkout 뷰를 렌더링하고 필수 모델 속성을 모두 설정한다")
        void checkout_withItems_rendersCheckoutView() throws Exception {
            // given: 장바구니에 상품 2건, 사용자 정보, 사용 가능 쿠폰 존재
            List<Cart> items = List.of(createCartItem(1L), createCartItem(2L));
            User user = createUser();
            BigDecimal totalPrice = new BigDecimal("60000");
            BigDecimal shippingFee = new BigDecimal("3000");
            BigDecimal finalAmount = new BigDecimal("63000");
            List<UserCoupon> coupons = createAvailableCoupons();

            when(cartService.getSelectedCartItems(USER_ID, null)).thenReturn(items);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(cartService.calculateTotal(items)).thenReturn(totalPrice);
            when(orderService.calculateShippingFee(user.getTier(), totalPrice)).thenReturn(shippingFee);
            when(orderService.calculateFinalAmount(totalPrice, BigDecimal.ZERO, shippingFee)).thenReturn(finalAmount);
            when(couponService.getAvailableCoupons(USER_ID)).thenReturn(coupons);

            // when & then: checkout 뷰와 주문에 필요한 모든 모델 속성이 존재하는지 검증
            mockMvc.perform(get("/orders/checkout"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("order/checkout"))
                    .andExpect(model().attributeExists(
                            "cartItems", "cartItemIds", "totalPrice",
                            "estimatedShippingFee", "estimatedFinalAmount",
                            "user", "pointBalance", "availableCoupons",
                            "couponDisplayNames", "paymentMethods"))
                    .andExpect(model().attribute("totalPrice", totalPrice))
                    .andExpect(model().attribute("estimatedShippingFee", shippingFee))
                    .andExpect(model().attribute("estimatedFinalAmount", finalAmount))
                    // paymentMethods에 PaymentMethod enum 전체가 포함되어야 한다
                    .andExpect(model().attribute("paymentMethods",
                            hasSize(PaymentMethod.values().length)));
        }

        @Test
        @DisplayName("cartItemIds 파라미터로 선택 항목만 조회할 수 있다")
        void checkout_withCartItemIds_filtersSelectedItems() throws Exception {
            // given: cartItemIds=[1,3]으로 특정 항목만 선택
            List<Long> selectedIds = List.of(1L, 3L);
            List<Cart> items = List.of(createCartItem(1L), createCartItem(3L));
            User user = createUser();
            BigDecimal totalPrice = new BigDecimal("60000");

            when(cartService.getSelectedCartItems(USER_ID, selectedIds)).thenReturn(items);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(cartService.calculateTotal(items)).thenReturn(totalPrice);
            when(orderService.calculateShippingFee(any(), any())).thenReturn(BigDecimal.ZERO);
            when(orderService.calculateFinalAmount(any(), any(), any())).thenReturn(totalPrice);
            when(couponService.getAvailableCoupons(USER_ID)).thenReturn(Collections.emptyList());

            // when & then: 선택된 항목의 cartId만 cartItemIds 모델 속성에 포함
            mockMvc.perform(get("/orders/checkout")
                            .param("cartItemIds", "1", "3"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("order/checkout"))
                    .andExpect(model().attribute("cartItemIds", List.of(1L, 3L)));
        }

        @Test
        @DisplayName("장바구니가 비어있으면 /cart로 리다이렉트한다")
        void checkout_emptyCart_redirectsToCart() throws Exception {
            // given: 빈 장바구니
            when(cartService.getSelectedCartItems(eq(USER_ID), any())).thenReturn(Collections.emptyList());

            // when & then: 장바구니로 리다이렉트
            mockMvc.perform(get("/orders/checkout"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/cart"));

            // 빈 장바구니일 때는 사용자 조회나 금액 계산을 수행하지 않아야 한다
            verify(userService, never()).findById(anyLong());
            verify(orderService, never()).calculateShippingFee(any(), any());
        }
    }

    // ── POST /orders ────────────────────────────────────────────

    @Nested
    @DisplayName("POST /orders — 주문 생성")
    class CreateOrderTests {

        @Test
        @DisplayName("정상 요청 시 주문을 생성하고 주문 상세 페이지로 리다이렉트한다")
        void createOrder_validRequest_redirectsToOrderDetail() throws Exception {
            // given: OrderService.createOrder가 정상적으로 Order를 반환
            Order order = createOrder();
            when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class))).thenReturn(order);

            // when & then: 주문 상세 페이지(/orders/{orderId})로 리다이렉트
            mockMvc.perform(post("/orders")
                            .param("shippingAddress", "서울시 강남구")
                            .param("recipientName", "홍길동")
                            .param("recipientPhone", "010-1234-5678")
                            .param("paymentMethod", "CARD"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("successMessage", "주문이 완료되었습니다."));
        }

        @Test
        @DisplayName("필수 입력값이 누락되면 checkout으로 리다이렉트하고 에러 메시지를 전달한다")
        void createOrder_missingRequiredFields_redirectsWithError() throws Exception {
            // given: shippingAddress(필수)가 빈 문자열
            // when & then: @Valid 검증 실패 → 리다이렉트 + flash 에러 메시지
            mockMvc.perform(post("/orders")
                            .param("shippingAddress", "")
                            .param("recipientName", "홍길동")
                            .param("recipientPhone", "010-1234-5678")
                            .param("paymentMethod", "CARD"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/checkout"))
                    .andExpect(flash().attribute("errorMessage", "입력값을 확인해주세요."));

            // 검증 실패 시 OrderService.createOrder가 호출되지 않아야 한다
            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @Test
        @DisplayName("수령인 이름이 누락되면 검증 실패로 리다이렉트한다")
        void createOrder_missingRecipientName_redirectsWithError() throws Exception {
            mockMvc.perform(post("/orders")
                            .param("shippingAddress", "서울시 강남구")
                            .param("recipientName", "")
                            .param("recipientPhone", "010-1234-5678")
                            .param("paymentMethod", "CARD"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/checkout"))
                    .andExpect(flash().attribute("errorMessage", "입력값을 확인해주세요."));

            verify(orderService, never()).createOrder(anyLong(), any());
        }

        @Test
        @DisplayName("BusinessException 발생 시 checkout으로 리다이렉트하고 비즈니스 에러 메시지를 전달한다")
        void createOrder_businessException_redirectsWithErrorMessage() throws Exception {
            // given: 재고 부족 등 비즈니스 예외 발생
            when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class)))
                    .thenThrow(new BusinessException("STOCK_NOT_ENOUGH", "재고가 부족합니다."));

            // when & then: 비즈니스 예외 메시지가 flash attribute로 전달
            mockMvc.perform(post("/orders")
                            .param("shippingAddress", "서울시 강남구")
                            .param("recipientName", "홍길동")
                            .param("recipientPhone", "010-1234-5678")
                            .param("paymentMethod", "CARD"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/checkout"))
                    .andExpect(flash().attribute("errorMessage", "재고가 부족합니다."));
        }

        @Test
        @DisplayName("포인트와 쿠폰을 함께 사용하는 주문도 정상 처리된다")
        void createOrder_withPointsAndCoupon_success() throws Exception {
            // given: 포인트 1000점 + 쿠폰 사용
            Order order = createOrder();
            when(orderService.createOrder(eq(USER_ID), any(OrderCreateRequest.class))).thenReturn(order);

            // when & then: 추가 파라미터(usePoints, userCouponId)와 함께 정상 처리
            mockMvc.perform(post("/orders")
                            .param("shippingAddress", "서울시 강남구")
                            .param("recipientName", "홍길동")
                            .param("recipientPhone", "010-1234-5678")
                            .param("paymentMethod", "KAKAO")
                            .param("usePoints", "1000")
                            .param("userCouponId", "10"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID));
        }
    }

    // ── GET /orders ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /orders — 주문 목록")
    class OrderListTests {

        @Test
        @DisplayName("주문 목록 페이지를 렌더링하고 필수 모델 속성을 설정한다")
        void orderList_rendersListView() throws Exception {
            // given: 사용자의 주문 1건이 존재
            Order order = createOrder();
            Page<Order> page = new PageImpl<>(List.of(order), PageRequest.of(0, 10), 1);
            when(orderService.getOrdersByUser(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then: list 뷰 + orders, orderStatusLabels, orderStatusBadgeClasses 모델 속성
            mockMvc.perform(get("/orders").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("order/list"))
                    .andExpect(model().attributeExists("orders", "orderStatusLabels", "orderStatusBadgeClasses"));
        }

        @Test
        @DisplayName("page 파라미터 없이 요청하면 기본값 0 페이지를 조회한다")
        void orderList_noPageParam_defaultsToZero() throws Exception {
            // given
            Page<Order> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(orderService.getOrdersByUser(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then: page 파라미터 생략 시 기본값 0으로 동작
            mockMvc.perform(get("/orders"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("order/list"));
        }
    }

    // ── GET /orders/{orderId} ───────────────────────────────────

    @Nested
    @DisplayName("GET /orders/{orderId} — 주문 상세")
    class OrderDetailTests {

        @Test
        @DisplayName("주문 상세 페이지를 렌더링하고 order 모델 속성을 설정한다")
        void orderDetail_rendersDetailView() throws Exception {
            // given: 해당 주문이 현재 사용자 소유
            Order order = createOrder();
            when(orderService.getOrderDetail(ORDER_ID, USER_ID)).thenReturn(order);

            // when & then: detail 뷰 + order 속성
            mockMvc.perform(get("/orders/{orderId}", ORDER_ID))
                    .andExpect(status().isOk())
                    .andExpect(view().name("order/detail"))
                    .andExpect(model().attributeExists("order"));
        }
    }

    // ── POST /orders/{orderId}/cancel ───────────────────────────

    @Nested
    @DisplayName("POST /orders/{orderId}/cancel — 주문 취소")
    class CancelOrderTests {

        @Test
        @DisplayName("주문 취소 성공 시 주문 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void cancelOrder_success_redirectsWithSuccessMessage() throws Exception {
            // given: 취소 가능한 주문
            doNothing().when(orderService).cancelOrder(ORDER_ID, USER_ID);

            // when & then
            mockMvc.perform(post("/orders/{orderId}/cancel", ORDER_ID))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("successMessage", "주문이 취소되었습니다."));
        }

        @Test
        @DisplayName("이미 배송 중인 주문 취소 시 에러 메시지를 전달한다")
        void cancelOrder_alreadyShipped_redirectsWithErrorMessage() throws Exception {
            // given: 배송 중 주문은 취소 불가 → BusinessException 발생
            doThrow(new BusinessException("ORDER_NOT_CANCELLABLE", "배송 중인 주문은 취소할 수 없습니다."))
                    .when(orderService).cancelOrder(ORDER_ID, USER_ID);

            // when & then: 에러 메시지가 flash attribute로 전달
            mockMvc.perform(post("/orders/{orderId}/cancel", ORDER_ID))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("errorMessage", "배송 중인 주문은 취소할 수 없습니다."));
        }
    }

    // ── POST /orders/{orderId}/partial-cancel ───────────────────

    @Nested
    @DisplayName("POST /orders/{orderId}/partial-cancel — 부분 취소")
    class PartialCancelTests {

        @Test
        @DisplayName("부분 취소 성공 시 주문 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void partialCancel_success() throws Exception {
            // given
            doNothing().when(orderService).partialCancel(ORDER_ID, USER_ID, 50L, 1);

            // when & then
            mockMvc.perform(post("/orders/{orderId}/partial-cancel", ORDER_ID)
                            .param("orderItemId", "50")
                            .param("quantity", "1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("successMessage", "부분 취소가 완료되었습니다."));
        }

        @Test
        @DisplayName("부분 취소 실패 시 에러 메시지를 전달한다")
        void partialCancel_businessException() throws Exception {
            // given: 취소 수량이 남은 수량 초과 등 비즈니스 예외
            doThrow(new BusinessException("INVALID_QUANTITY", "취소 수량이 남은 수량을 초과합니다."))
                    .when(orderService).partialCancel(ORDER_ID, USER_ID, 50L, 5);

            // when & then
            mockMvc.perform(post("/orders/{orderId}/partial-cancel", ORDER_ID)
                            .param("orderItemId", "50")
                            .param("quantity", "5"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("errorMessage", "취소 수량이 남은 수량을 초과합니다."));
        }
    }

    // ── POST /orders/{orderId}/return ───────────────────────────

    @Nested
    @DisplayName("POST /orders/{orderId}/return — 반품 신청")
    class ReturnTests {

        @Test
        @DisplayName("반품 신청 성공 시 주문 상세로 리다이렉트하고 성공 메시지를 전달한다")
        void requestReturn_success() throws Exception {
            // given
            doNothing().when(orderService).requestReturn(ORDER_ID, USER_ID, 50L, 1, "DEFECT");

            // when & then
            mockMvc.perform(post("/orders/{orderId}/return", ORDER_ID)
                            .param("orderItemId", "50")
                            .param("quantity", "1")
                            .param("returnReason", "DEFECT"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("successMessage",
                            "반품 신청이 접수되었습니다. 관리자 승인 후 처리됩니다."));
        }

        @Test
        @DisplayName("returnReason 파라미터가 없으면 기본값 OTHER가 사용된다")
        void requestReturn_defaultReason_usesOther() throws Exception {
            // given: returnReason 생략 시 defaultValue="OTHER"
            doNothing().when(orderService).requestReturn(ORDER_ID, USER_ID, 50L, 1, "OTHER");

            // when & then
            mockMvc.perform(post("/orders/{orderId}/return", ORDER_ID)
                            .param("orderItemId", "50")
                            .param("quantity", "1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("successMessage",
                            "반품 신청이 접수되었습니다. 관리자 승인 후 처리됩니다."));
        }

        @Test
        @DisplayName("반품 신청 실패 시 에러 메시지를 전달한다")
        void requestReturn_businessException() throws Exception {
            // given: 반품 불가 상태 등 비즈니스 예외
            doThrow(new BusinessException("RETURN_NOT_ALLOWED", "반품 가능 기간이 지났습니다."))
                    .when(orderService).requestReturn(ORDER_ID, USER_ID, 50L, 1, "CHANGE_OF_MIND");

            // when & then
            mockMvc.perform(post("/orders/{orderId}/return", ORDER_ID)
                            .param("orderItemId", "50")
                            .param("quantity", "1")
                            .param("returnReason", "CHANGE_OF_MIND"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/orders/" + ORDER_ID))
                    .andExpect(flash().attribute("errorMessage", "반품 가능 기간이 지났습니다."));
        }
    }

    // ── buildCouponDisplayNames 간접 검증 ───────────────────────

    @Nested
    @DisplayName("buildCouponDisplayNames — 쿠폰 표시명 포맷 검증")
    class CouponDisplayNameTests {

        @Test
        @DisplayName("PERCENT 쿠폰과 FIXED 쿠폰의 표시명이 올바르게 포맷된다")
        void checkout_couponDisplayNames_formatsCorrectly() throws Exception {
            // given: PERCENT(10%) + FIXED(3,000원) 쿠폰
            List<Cart> items = List.of(createCartItem(1L));
            User user = createUser();
            BigDecimal totalPrice = new BigDecimal("30000");
            List<UserCoupon> coupons = createAvailableCoupons();

            when(cartService.getSelectedCartItems(USER_ID, null)).thenReturn(items);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(cartService.calculateTotal(items)).thenReturn(totalPrice);
            when(orderService.calculateShippingFee(any(), any())).thenReturn(BigDecimal.ZERO);
            when(orderService.calculateFinalAmount(any(), any(), any())).thenReturn(totalPrice);
            when(couponService.getAvailableCoupons(USER_ID)).thenReturn(coupons);

            // when & then: couponDisplayNames 맵에 정률/정액 포맷이 올바르게 포함
            // 주의: hasEntry() 안에서 Mockito.eq()가 아니라 Hamcrest.equalTo()를 써야 한다.
            // Mockito.eq()는 stubbing/verify 밖에서 호출하면 0L을 반환하고,
            // 이후 테스트의 Mockito 내부 매처 상태까지 오염시킨다.
            mockMvc.perform(get("/orders/checkout"))
                    .andExpect(status().isOk())
                    // PERCENT 쿠폰: "10% 할인" 텍스트 포함
                    .andExpect(model().attribute("couponDisplayNames",
                            hasEntry(equalTo(10L), containsString("10%"))))
                    // FIXED 쿠폰: "3,000원 할인" 텍스트 포함 (천 단위 콤마)
                    .andExpect(model().attribute("couponDisplayNames",
                            hasEntry(equalTo(11L), containsString("3,000원"))));
        }

        @Test
        @DisplayName("사용 가능 쿠폰이 없으면 빈 맵이 설정된다")
        void checkout_noCoupons_emptyDisplayNames() throws Exception {
            // given: 사용 가능 쿠폰 없음
            List<Cart> items = List.of(createCartItem(1L));
            User user = createUser();
            BigDecimal totalPrice = new BigDecimal("30000");

            when(cartService.getSelectedCartItems(USER_ID, null)).thenReturn(items);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(cartService.calculateTotal(items)).thenReturn(totalPrice);
            when(orderService.calculateShippingFee(any(), any())).thenReturn(BigDecimal.ZERO);
            when(orderService.calculateFinalAmount(any(), any(), any())).thenReturn(totalPrice);
            when(couponService.getAvailableCoupons(USER_ID)).thenReturn(Collections.emptyList());

            // when & then: 빈 맵
            mockMvc.perform(get("/orders/checkout"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("couponDisplayNames", anEmptyMap()));
        }
    }
}
