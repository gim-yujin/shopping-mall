package com.shop.domain.cart.controller;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.entity.Product;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.service.UserService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CartController(SSR) 단위 테스트.
 *
 * <p>4개 엔드포인트를 커버한다:
 * GET /cart(장바구니 페이지), POST /cart/add(추가),
 * POST /cart/update(수량 변경, @ResponseBody), POST /cart/remove(삭제).</p>
 *
 * <p>GET /cart는 UserService와 OrderService도 호출하여
 * 배송비 + 무료배송 기준금액을 계산하므로, User와 UserTier 픽스처가 필요하다.</p>
 *
 * <p>커버리지 목표: 46% → 100% (28라인 전체)</p>
 */
@ExtendWith(MockitoExtension.class)
class CartControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CartService cartService;
    @Mock
    private UserService userService;
    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CartController controller = new CartController(cartService, userService, orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

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
        SecurityContextHolder.clearContext();
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    /**
     * UserTier는 protected 기본 생성자만 있으므로 리플렉션으로 생성한다.
     * getFreeShippingThreshold()가 호출되므로 반드시 설정해야 한다.
     */
    private UserTier createUserTier() {
        try {
            var constructor = UserTier.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            UserTier tier = constructor.newInstance();
            ReflectionTestUtils.setField(tier, "tierName", "BASIC");
            ReflectionTestUtils.setField(tier, "tierLevel", 1);
            ReflectionTestUtils.setField(tier, "minSpent", BigDecimal.ZERO);
            ReflectionTestUtils.setField(tier, "freeShippingThreshold", new BigDecimal("50000"));
            return tier;
        } catch (Exception e) {
            throw new RuntimeException("UserTier 인스턴스 생성 실패", e);
        }
    }

    private User createUser() {
        User user = new User("tester", "tester@example.com", "encoded", "테스터", "010-1111-2222");
        user.setTier(createUserTier());
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        return user;
    }

    private Cart createCartItem(Long cartId, Long productId) {
        Product product = mock(Product.class);
        lenient().when(product.getProductId()).thenReturn(productId);
        lenient().when(product.getProductName()).thenReturn("상품 " + productId);
        lenient().when(product.getPrice()).thenReturn(new BigDecimal("15000"));
        lenient().when(product.getThumbnailUrl()).thenReturn("/img/" + productId + ".jpg");

        Cart cart = new Cart(USER_ID, product, 2);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    // ── GET /cart ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /cart — 장바구니 페이지")
    class CartPageTests {

        @Test
        @DisplayName("장바구니 페이지를 렌더링하고 배송비/무료배송 기준 모델 속성을 설정한다")
        void cartPage_rendersWithShippingInfo() throws Exception {
            // given: 장바구니에 상품 1건, 배송비 3,000원
            List<Cart> items = List.of(createCartItem(1L, 100L));
            BigDecimal totalPrice = new BigDecimal("30000");
            BigDecimal shippingFee = new BigDecimal("3000");
            User user = createUser();

            when(cartService.getCartItems(USER_ID)).thenReturn(items);
            when(cartService.calculateTotal(items)).thenReturn(totalPrice);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(orderService.calculateShippingFee(user.getTier(), totalPrice)).thenReturn(shippingFee);

            // when & then: 장바구니 뷰 + 배송비/무료배송기준 모델 속성
            mockMvc.perform(get("/cart"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("cart/index"))
                    .andExpect(model().attributeExists("cartItems", "totalPrice", "shippingFee", "freeShippingThreshold"))
                    .andExpect(model().attribute("totalPrice", totalPrice))
                    .andExpect(model().attribute("shippingFee", shippingFee));
        }

        @Test
        @DisplayName("빈 장바구니도 정상적으로 렌더링한다")
        void cartPage_empty() throws Exception {
            // given: 빈 장바구니
            User user = createUser();
            when(cartService.getCartItems(USER_ID)).thenReturn(Collections.emptyList());
            when(cartService.calculateTotal(Collections.emptyList())).thenReturn(BigDecimal.ZERO);
            when(userService.findById(USER_ID)).thenReturn(user);
            when(orderService.calculateShippingFee(any(), any())).thenReturn(new BigDecimal("3000"));

            // when & then
            mockMvc.perform(get("/cart"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("cart/index"));
        }
    }

    // ── POST /cart/add ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /cart/add — 장바구니 추가")
    class AddToCartTests {

        @Test
        @DisplayName("추가 성공 시 장바구니로 리다이렉트하고 성공 메시지를 전달한다")
        void addToCart_success() throws Exception {
            // given
            doNothing().when(cartService).addToCart(USER_ID, 100L, 2);

            // when & then
            mockMvc.perform(post("/cart/add")
                            .param("productId", "100")
                            .param("quantity", "2"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/cart"))
                    .andExpect(flash().attribute("successMessage", "장바구니에 추가되었습니다."));

            verify(cartService).addToCart(USER_ID, 100L, 2);
        }

        @Test
        @DisplayName("quantity 파라미터 생략 시 기본값 1이 적용된다")
        void addToCart_defaultQuantity() throws Exception {
            // given: quantity 파라미터 생략 → defaultValue = "1"
            doNothing().when(cartService).addToCart(USER_ID, 100L, 1);

            // when & then
            mockMvc.perform(post("/cart/add")
                            .param("productId", "100"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/cart"));

            verify(cartService).addToCart(USER_ID, 100L, 1);
        }
    }

    // ── POST /cart/update ───────────────────────────────────────

    @Nested
    @DisplayName("POST /cart/update — 수량 변경 (@ResponseBody)")
    class UpdateQuantityTests {

        @Test
        @DisplayName("수량 변경 성공 시 JSON 응답(success, totalPrice, cartCount)을 반환한다")
        void updateQuantity_success_returnsJson() throws Exception {
            // given: 수량 변경 후 갱신된 장바구니
            doNothing().when(cartService).updateQuantity(USER_ID, 100L, 3);
            List<Cart> items = List.of(createCartItem(1L, 100L));
            when(cartService.getCartItems(USER_ID)).thenReturn(items);
            when(cartService.calculateTotal(items)).thenReturn(new BigDecimal("45000"));

            // when & then: @ResponseBody이므로 JSON 응답
            mockMvc.perform(post("/cart/update")
                            .param("productId", "100")
                            .param("quantity", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.totalPrice").value(45000))
                    .andExpect(jsonPath("$.cartCount").value(1));
        }
    }

    // ── POST /cart/remove ───────────────────────────────────────

    @Nested
    @DisplayName("POST /cart/remove — 상품 삭제")
    class RemoveFromCartTests {

        @Test
        @DisplayName("삭제 성공 시 장바구니로 리다이렉트하고 성공 메시지를 전달한다")
        void removeFromCart_success() throws Exception {
            // given
            doNothing().when(cartService).removeFromCart(USER_ID, 100L);

            // when & then
            mockMvc.perform(post("/cart/remove")
                            .param("productId", "100"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/cart"))
                    .andExpect(flash().attribute("successMessage", "상품이 삭제되었습니다."));

            verify(cartService).removeFromCart(USER_ID, 100L);
        }
    }
}
