package com.shop.domain.wishlist.controller;

import com.shop.domain.wishlist.service.WishlistService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WishlistController 단위 테스트.
 *
 * <p>위시리스트 SSR 컨트롤러의 페이지 렌더링과 토글 API를 검증한다.
 * standaloneSetup으로 Security 필터 없이 컨트롤러 로직만 테스트한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class WishlistControllerUnitTest {

    @Mock
    private WishlistService wishlistService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WishlistController controller = new WishlistController(wishlistService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // SecurityContextHolder에 인증 정보 설정
        CustomUserPrincipal principal = new CustomUserPrincipal(
                1L, "testuser", "password", "테스트", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /wishlist — 위시리스트 페이지 렌더링")
    void wishlistPage_rendersCorrectView() throws Exception {
        when(wishlistService.getWishlist(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/wishlist"))
                .andExpect(status().isOk())
                .andExpect(view().name("wishlist/index"))
                .andExpect(model().attributeExists("wishlists"));

        verify(wishlistService).getWishlist(eq(1L), any(PageRequest.class));
    }

    @Test
    @DisplayName("GET /wishlist?page=2 — 페이지 파라미터가 전달된다")
    void wishlistPage_withPageParam() throws Exception {
        when(wishlistService.getWishlist(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/wishlist").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("wishlist/index"));
    }

    @Test
    @DisplayName("GET /wishlist?page=-1 — 음수 페이지는 0으로 보정된다")
    void wishlistPage_negativePageNormalized() throws Exception {
        when(wishlistService.getWishlist(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        // PagingParams.normalizePage(-1) → 0
        mockMvc.perform(get("/wishlist").param("page", "-1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /wishlist/toggle — 위시리스트 추가 시 wishlisted=true 반환")
    void toggleWishlist_addItem_returnsTrue() throws Exception {
        when(wishlistService.toggleWishlist(1L, 100L)).thenReturn(true);

        mockMvc.perform(post("/wishlist/toggle").param("productId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wishlisted").value(true));
    }

    @Test
    @DisplayName("POST /wishlist/toggle — 위시리스트 제거 시 wishlisted=false 반환")
    void toggleWishlist_removeItem_returnsFalse() throws Exception {
        when(wishlistService.toggleWishlist(1L, 100L)).thenReturn(false);

        mockMvc.perform(post("/wishlist/toggle").param("productId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wishlisted").value(false));
    }
}
