package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.product.service.ViewCountService;
import com.shop.domain.review.entity.Review;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.wishlist.service.WishlistService;
import com.shop.global.backpressure.BackpressureDetector;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ProductController 단위 테스트.
 *
 * <p>상품 목록(listProducts)은 기존 통합 테스트에서 커버되지만,
 * 상품 상세(productDetail)는 다수의 분기(백프레셔, 인증 여부, 카테고리 유무)가 있어
 * 단위 테스트로 분기 커버리지를 보강한다.</p>
 *
 * <p>standaloneSetup으로 Security 필터 없이 컨트롤러 로직만 테스트한다.
 * 인증이 필요한 분기는 SecurityContextHolder에 직접 인증 정보를 설정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerUnitTest {

    @Mock
    private ProductQueryService productQueryService;
    @Mock
    private CategoryService categoryService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private WishlistService wishlistService;
    @Mock
    private ViewCountService viewCountService;
    @Mock
    private BackpressureDetector backpressureDetector;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductController controller = new ProductController(
                productQueryService, categoryService, reviewService,
                wishlistService, viewCountService, backpressureDetector);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 인증 정보를 SecurityContextHolder에 설정한다. */
    private void authenticateUser(Long userId) {
        CustomUserPrincipal principal = new CustomUserPrincipal(
                userId, "testuser", "password", "테스트", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** 테스트용 CachedProductDetail을 생성한다. */
    private CachedProductDetail createCachedProduct(Integer categoryId) {
        return new CachedProductDetail(
                1L, "테스트 상품", "상품 설명",
                new BigDecimal("30000"), new BigDecimal("35000"), 14,
                100, 50, 1000, new BigDecimal("4.5"), 20,
                true, "/images/product1.jpg",
                categoryId, categoryId != null ? "전자제품" : null,
                LocalDateTime.now());
    }

    // ── GET /products ──

    @Nested
    @DisplayName("GET /products — 상품 목록")
    class ListProductsTests {

        @Test
        @DisplayName("기본 파라미터로 상품 목록을 렌더링한다")
        void listProducts_defaultParams_rendersListView() throws Exception {
            when(productQueryService.findAllSorted(0, 20, "best"))
                    .thenReturn(Page.empty());
            when(categoryService.getTopLevelCategories())
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("product/list"))
                    .andExpect(model().attributeExists("products", "categories", "currentSort", "baseUrl"));
        }

        @Test
        @DisplayName("정렬/페이징 파라미터가 전달된다")
        void listProducts_withParams_passesSortAndPage() throws Exception {
            when(productQueryService.findAllSorted(2, 10, "price_asc"))
                    .thenReturn(Page.empty());
            when(categoryService.getTopLevelCategories())
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/products")
                            .param("page", "2")
                            .param("size", "10")
                            .param("sort", "price_asc"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("currentSort", "price_asc"));
        }
    }

    // ── GET /products/{productId} ──

    @Nested
    @DisplayName("GET /products/{productId} — 상품 상세")
    class ProductDetailTests {

        @Test
        @DisplayName("비인증 사용자 — 상품 상세 렌더링 + 조회수 증가 (백프레셔 정상)")
        void productDetail_anonymous_rendersDetail() throws Exception {
            CachedProductDetail product = createCachedProduct(10);
            Page<Review> reviews = new PageImpl<>(Collections.emptyList());

            when(productQueryService.findByIdCached(1L)).thenReturn(product);
            // 백프레셔 정상 → 조회수 증가 실행
            when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
            when(reviewService.getProductReviews(eq(1L), any(PageRequest.class)))
                    .thenReturn(reviews);
            when(categoryService.getBreadcrumb(10)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/products/1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("product/detail"))
                    .andExpect(model().attributeExists("product", "reviews", "helpedReviewIds"))
                    .andExpect(model().attribute("product", product));

            // 백프레셔 정상이므로 조회수 증가가 호출됨
            verify(viewCountService).incrementAsync(1L);
            // 카테고리가 있으므로 breadcrumb가 설정됨
            verify(categoryService).getBreadcrumb(10);
        }

        @Test
        @DisplayName("백프레셔 CRITICAL — 조회수 증가를 건너뛴다")
        void productDetail_criticalBackpressure_skipsViewCount() throws Exception {
            CachedProductDetail product = createCachedProduct(10);
            Page<Review> reviews = new PageImpl<>(Collections.emptyList());

            when(productQueryService.findByIdCached(1L)).thenReturn(product);
            // 백프레셔 CRITICAL → shouldShedNonCritical()이 true 반환
            when(backpressureDetector.shouldShedNonCritical()).thenReturn(true);
            when(reviewService.getProductReviews(eq(1L), any(PageRequest.class)))
                    .thenReturn(reviews);
            when(categoryService.getBreadcrumb(10)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/products/1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("product/detail"));

            // CRITICAL 상태이므로 조회수 증가가 호출되지 않음
            verify(viewCountService, never()).incrementAsync(anyLong());
        }

        @Test
        @DisplayName("인증된 사용자 — 위시리스트 상태, 리뷰 가능 아이템, 도움 준 리뷰 설정")
        void productDetail_authenticated_setsUserSpecificAttributes() throws Exception {
            authenticateUser(5L);
            CachedProductDetail product = createCachedProduct(10);
            Page<Review> reviews = new PageImpl<>(Collections.emptyList());

            when(productQueryService.findByIdCached(1L)).thenReturn(product);
            when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
            when(reviewService.getProductReviews(eq(1L), any(PageRequest.class)))
                    .thenReturn(reviews);
            when(wishlistService.isWishlisted(5L, 1L)).thenReturn(true);
            when(reviewService.getHelpedReviewIds(eq(5L), any(Set.class)))
                    .thenReturn(Set.of());
            when(reviewService.getReviewableOrderItems(5L, 1L))
                    .thenReturn(Collections.emptyList());
            when(categoryService.getBreadcrumb(10)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/products/1"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("currentUserId", 5L))
                    .andExpect(model().attribute("isWishlisted", true))
                    .andExpect(model().attributeExists("reviewableOrderItems"));

            verify(wishlistService).isWishlisted(5L, 1L);
            verify(reviewService).getReviewableOrderItems(5L, 1L);
        }

        @Test
        @DisplayName("카테고리가 null인 상품 — breadcrumb을 설정하지 않는다")
        void productDetail_nullCategory_skipsBreadcrumb() throws Exception {
            // categoryId가 null인 상품 (카테고리 미분류)
            CachedProductDetail product = createCachedProduct(null);
            Page<Review> reviews = new PageImpl<>(Collections.emptyList());

            when(productQueryService.findByIdCached(1L)).thenReturn(product);
            when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
            when(reviewService.getProductReviews(eq(1L), any(PageRequest.class)))
                    .thenReturn(reviews);

            mockMvc.perform(get("/products/1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("product/detail"));

            // categoryId가 null이므로 getBreadcrumb이 호출되지 않음
            verify(categoryService, never()).getBreadcrumb(anyInt());
        }

        @Test
        @DisplayName("리뷰 페이지 음수 → 0으로 보정된다")
        void productDetail_negativeReviewPage_normalizedToZero() throws Exception {
            CachedProductDetail product = createCachedProduct(null);
            Page<Review> reviews = new PageImpl<>(Collections.emptyList());

            when(productQueryService.findByIdCached(1L)).thenReturn(product);
            when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
            // normalizePage(-1) → 0 → PageRequest.of(0, ...)
            when(reviewService.getProductReviews(eq(1L), eq(PageRequest.of(0, 10))))
                    .thenReturn(reviews);

            mockMvc.perform(get("/products/1").param("reviewPage", "-1"))
                    .andExpect(status().isOk());

            // 보정된 페이지(0)로 리뷰 조회가 호출됨
            verify(reviewService).getProductReviews(eq(1L), eq(PageRequest.of(0, 10)));
        }
    }
}
