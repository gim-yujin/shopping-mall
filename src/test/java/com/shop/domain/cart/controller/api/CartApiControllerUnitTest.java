package com.shop.domain.cart.controller.api;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.service.CartService;
import com.shop.domain.product.entity.Product;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CartApiController 단위 테스트.
 *
 * <p>장바구니 REST API의 4개 엔드포인트를 검증한다:
 * GET(조회), POST(추가), PUT(수량변경), DELETE(제거).
 * 각 변경 API는 변경 후 갱신된 장바구니 상태를 반환하는 패턴을 사용하므로,
 * CartService.getCartItems + calculateTotal이 항상 후속 호출되는지도 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CartApiControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CartService cartService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CartApiController controller = new CartApiController(cartService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        // SecurityUtil.getCurrentUserId()가 USER_ID를 반환하도록 인증 컨텍스트 설정
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

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────

    /**
     * Cart 엔티티를 생성한다.
     * CartItemResponse.from(cart)이 Product의 getter를 호출하므로
     * Product를 mock으로 구성한다.
     */
    private Cart createCartItem(Long cartId, Long productId, int quantity) {
        Product product = mock(Product.class);
        lenient().when(product.getProductId()).thenReturn(productId);
        lenient().when(product.getProductName()).thenReturn("테스트 상품 " + productId);
        lenient().when(product.getPrice()).thenReturn(new BigDecimal("15000"));
        lenient().when(product.getThumbnailUrl()).thenReturn("/img/product" + productId + ".jpg");

        Cart cart = new Cart(USER_ID, product, quantity);
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    /**
     * CartService가 장바구니 상태를 반환하도록 공통 스텁을 설정한다.
     * POST/PUT 엔드포인트는 변경 후 getCartItems + calculateTotal을 호출하므로
     * 이 스텁이 필요하다.
     */
    private List<Cart> stubCartState() {
        List<Cart> items = List.of(createCartItem(1L, 100L, 2));
        when(cartService.getCartItems(USER_ID)).thenReturn(items);
        when(cartService.calculateTotal(items)).thenReturn(new BigDecimal("30000"));
        return items;
    }

    // ── GET /api/v1/cart ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/cart — 장바구니 조회")
    class GetCartTests {

        @Test
        @DisplayName("장바구니에 상품이 있으면 항목 목록과 합계를 반환한다")
        void getCart_withItems_returnsCartResponse() throws Exception {
            // given: 장바구니에 상품 1건
            stubCartState();

            // when & then: items 배열, totalPrice, itemCount 검증
            mockMvc.perform(get("/api/v1/cart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items[0].productId").value(100))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.data.totalPrice").value(30000))
                    .andExpect(jsonPath("$.data.itemCount").value(1));
        }

        @Test
        @DisplayName("장바구니가 비어있으면 빈 배열과 0원을 반환한다")
        void getCart_empty_returnsEmptyResponse() throws Exception {
            // given: 빈 장바구니
            when(cartService.getCartItems(USER_ID)).thenReturn(Collections.emptyList());
            when(cartService.calculateTotal(Collections.emptyList())).thenReturn(BigDecimal.ZERO);

            // when & then
            mockMvc.perform(get("/api/v1/cart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.totalPrice").value(0))
                    .andExpect(jsonPath("$.data.itemCount").value(0));
        }
    }

    // ── POST /api/v1/cart ───────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/cart — 장바구니 추가")
    class AddToCartTests {

        @Test
        @DisplayName("상품 추가 성공 시 201 Created와 갱신된 장바구니를 반환한다")
        void addToCart_success_returns201() throws Exception {
            // given: addToCart 정상 동작 + 후속 장바구니 조회
            doNothing().when(cartService).addToCart(USER_ID, 100L, 2);
            stubCartState();

            String requestBody = """
                    { "productId": 100, "quantity": 2 }
                    """;

            // when & then: 201 + 갱신된 장바구니 상태 반환
            mockMvc.perform(post("/api/v1/cart")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items").isArray());

            // addToCart가 올바른 인자로 호출되었는지 검증
            verify(cartService).addToCart(USER_ID, 100L, 2);
        }

        @Test
        @DisplayName("productId 누락 시 400 Bad Request를 반환한다")
        void addToCart_missingProductId_returns400() throws Exception {
            // given: productId 없음 → @NotNull 위반
            String requestBody = """
                    { "quantity": 2 }
                    """;

            // when & then
            mockMvc.perform(post("/api/v1/cart")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(cartService, never()).addToCart(anyLong(), anyLong(), anyInt());
        }
    }

    // ── PUT /api/v1/cart/{productId} ────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/cart/{productId} — 수량 변경")
    class UpdateQuantityTests {

        @Test
        @DisplayName("수량 변경 성공 시 갱신된 장바구니를 반환한다")
        void updateQuantity_success() throws Exception {
            // given
            doNothing().when(cartService).updateQuantity(USER_ID, 100L, 5);
            stubCartState();

            // when & then: quantity를 RequestParam으로 전달
            mockMvc.perform(put("/api/v1/cart/{productId}", 100L)
                            .param("quantity", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.items").isArray());

            verify(cartService).updateQuantity(USER_ID, 100L, 5);
        }
    }

    // ── DELETE /api/v1/cart/{productId} ──────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/cart/{productId} — 상품 제거")
    class RemoveFromCartTests {

        @Test
        @DisplayName("상품 제거 성공 시 빈 데이터로 200 OK를 반환한다")
        void removeFromCart_success() throws Exception {
            // given
            doNothing().when(cartService).removeFromCart(USER_ID, 100L);

            // when & then: ApiResponse.ok() — data가 null
            mockMvc.perform(delete("/api/v1/cart/{productId}", 100L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(cartService).removeFromCart(USER_ID, 100L);
        }
    }
}
