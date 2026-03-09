package com.shop.domain.wishlist.controller.api;

import com.shop.domain.product.entity.Product;
import com.shop.domain.wishlist.entity.Wishlist;
import com.shop.domain.wishlist.service.WishlistService;
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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WishlistApiController 단위 테스트.
 *
 * <p>위시리스트 API는 2개 엔드포인트로 구성된다:
 * GET(목록 조회) — LARGE_LIST_SIZE(20)로 페이징,
 * POST /toggle — 위시리스트 추가/제거 토글.</p>
 *
 * <p>WishlistItemResponse.from()이 Wishlist → Product 연쇄 호출하므로,
 * Product를 mock으로 구성하여 getStockQuantity() 등이 NPE 없이 동작하도록 한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class WishlistApiControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private WishlistService wishlistService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WishlistApiController controller = new WishlistApiController(wishlistService);
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
     * Wishlist 엔티티를 생성한다.
     * WishlistItemResponse.from(wishlist)은 wishlist.getProduct()를 통해
     * productId, productName, price, thumbnailUrl, stockQuantity를 읽으므로
     * Product를 mock으로 구성해야 한다.
     */
    private Wishlist createWishlist(Long wishlistId, Long productId) {
        Product product = mock(Product.class);
        lenient().when(product.getProductId()).thenReturn(productId);
        lenient().when(product.getProductName()).thenReturn("위시리스트 상품 " + productId);
        lenient().when(product.getPrice()).thenReturn(new BigDecimal("25000"));
        lenient().when(product.getThumbnailUrl()).thenReturn("/img/wish" + productId + ".jpg");
        // 재고가 있는 상태로 설정 → inStock=true
        lenient().when(product.getStockQuantity()).thenReturn(10);

        Wishlist wishlist = new Wishlist(USER_ID, product);
        ReflectionTestUtils.setField(wishlist, "wishlistId", wishlistId);
        return wishlist;
    }

    // ── GET /api/v1/wishlist ────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/wishlist — 위시리스트 조회")
    class GetWishlistTests {

        @Test
        @DisplayName("위시리스트에 상품이 있으면 페이징된 목록을 반환한다")
        void getWishlist_withItems() throws Exception {
            // given: 위시리스트에 상품 1건
            Wishlist item = createWishlist(1L, 500L);
            Page<Wishlist> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
            when(wishlistService.getWishlist(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then: PageResponse 구조 + 상품 정보 확인
            mockMvc.perform(get("/api/v1/wishlist").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].wishlistId").value(1))
                    .andExpect(jsonPath("$.data.content[0].productId").value(500))
                    .andExpect(jsonPath("$.data.content[0].inStock").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }

        @Test
        @DisplayName("위시리스트가 비어있으면 빈 목록을 반환한다")
        void getWishlist_empty() throws Exception {
            // given
            Page<Wishlist> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0);
            when(wishlistService.getWishlist(eq(USER_ID), any(PageRequest.class))).thenReturn(page);

            // when & then
            mockMvc.perform(get("/api/v1/wishlist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ── POST /api/v1/wishlist/toggle ────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/wishlist/toggle — 위시리스트 토글")
    class ToggleWishlistTests {

        @Test
        @DisplayName("위시리스트에 추가 시 wishlisted=true를 반환한다")
        void toggle_addToWishlist() throws Exception {
            // given: 아직 위시리스트에 없는 상품 → 추가됨
            when(wishlistService.toggleWishlist(USER_ID, 500L)).thenReturn(true);

            // when & then
            mockMvc.perform(post("/api/v1/wishlist/toggle")
                            .param("productId", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.wishlisted").value(true));
        }

        @Test
        @DisplayName("위시리스트에서 제거 시 wishlisted=false를 반환한다")
        void toggle_removeFromWishlist() throws Exception {
            // given: 이미 위시리스트에 있는 상품 → 제거됨
            when(wishlistService.toggleWishlist(USER_ID, 500L)).thenReturn(false);

            // when & then
            mockMvc.perform(post("/api/v1/wishlist/toggle")
                            .param("productId", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.wishlisted").value(false));
        }
    }
}
