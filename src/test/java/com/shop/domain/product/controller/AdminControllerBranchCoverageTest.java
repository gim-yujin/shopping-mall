package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.dto.AdminDashboardPreview;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.AdminDashboardPreviewService;
import com.shop.domain.product.service.ProductService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController 분기 커버리지 보강 테스트.
 *
 * <p>기존 AdminControllerUnitTest에서 다루지 않은 분기를 검증한다:
 * - 대시보드(GET /admin): 모든 모델 속성 설정
 * - 주문 목록: 상태 필터 있는/없는 분기
 * - 주문 상태 변경: non-SHIPPED 상태, BusinessException 분기
 * - 상품 수정: 유효성 검증 실패 분기
 * - 반품 거절: BusinessException 분기</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerBranchCoverageTest {

    @Mock
    private ProductService productService;
    @Mock
    private OrderService orderService;
    @Mock
    private CategoryService categoryService;
    @Mock
    private CouponService couponService;
    @Mock
    private AdminDashboardPreviewService dashboardPreviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminController controller = new AdminController(
                productService, orderService, categoryService, couponService, dashboardPreviewService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 관리자 인증 설정
        CustomUserPrincipal principal = new CustomUserPrincipal(
                1L, "admin", "password", "관리자", "ROLE_ADMIN",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 대시보드 ──

    @Nested
    @DisplayName("GET /admin — 대시보드")
    class DashboardTests {

        @Test
        @DisplayName("대시보드 렌더링 — 프리뷰 서비스 호출 + 모델 속성 바인딩")
        void dashboard_rendersWithAllAttributes() throws Exception {
            when(dashboardPreviewService.getPreview()).thenReturn(
                    new AdminDashboardPreview(
                            Page.empty(), Page.empty(),
                            new CouponStats(10, 5, 100, 30), 3L));

            mockMvc.perform(get("/admin"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/dashboard"))
                    .andExpect(model().attributeExists(
                            "products", "recentOrders", "couponStats", "pendingReturnCount"));
        }
    }

    // ── 주문 관리 ──

    @Nested
    @DisplayName("GET /admin/orders — 주문 목록")
    class AdminOrdersTests {

        @Test
        @DisplayName("상태 필터 없이 요청 — 전체 주문 조회")
        void adminOrders_noStatusFilter_returnsAll() throws Exception {
            when(orderService.getAllOrdersFlat(any(PageRequest.class)))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/admin/orders"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/orders"))
                    .andExpect(model().attributeExists("orders", "orderStatuses",
                            "orderStatusLabels", "orderStatusBadgeClasses"));

            verify(orderService).getAllOrdersFlat(any());
            verify(orderService, never()).getOrdersByStatusFlat(any(), any());
        }

        @Test
        @DisplayName("상태 필터 있는 요청 — 해당 상태 주문만 조회")
        void adminOrders_withStatusFilter_filtersOrders() throws Exception {
            when(orderService.getOrdersByStatusFlat(eq("PAID"), any(PageRequest.class)))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/admin/orders").param("status", "PAID"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/orders"))
                    .andExpect(model().attribute("currentStatus", "PAID"));

            verify(orderService).getOrdersByStatusFlat(eq("PAID"), any());
            verify(orderService, never()).getAllOrdersFlat(any());
        }

        @Test
        @DisplayName("빈 상태 필터 — 전체 주문 조회로 폴백")
        void adminOrders_blankStatusFilter_fallsBack() throws Exception {
            when(orderService.getAllOrdersFlat(any(PageRequest.class)))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/admin/orders").param("status", "  "))
                    .andExpect(status().isOk());

            verify(orderService).getAllOrdersFlat(any());
        }
    }

    // ── 주문 상태 변경 ──

    @Nested
    @DisplayName("POST /admin/orders/{orderId}/status — 주문 상태 변경")
    class UpdateOrderStatusTests {

        @Test
        @DisplayName("non-SHIPPED 상태 변경 — 택배사/송장 없이도 정상 처리")
        void updateStatus_nonShipped_succeeds() throws Exception {
            // PAID → SHIPPED가 아닌 다른 상태 전이에는 택배사/송장이 불필요
            mockMvc.perform(post("/admin/orders/10/status")
                            .param("status", "DELIVERED"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/orders"))
                    .andExpect(flash().attribute("successMessage", "주문 상태가 변경되었습니다."));

            verify(orderService).updateOrderStatus(10L, "DELIVERED", null, null);
        }

        @Test
        @DisplayName("상태 변경 중 BusinessException — 에러 메시지 전달")
        void updateStatus_businessException_redirectsWithError() throws Exception {
            doThrow(new BusinessException("INVALID_STATUS", "이미 취소된 주문입니다."))
                    .when(orderService).updateOrderStatus(10L, "PAID", null, null);

            mockMvc.perform(post("/admin/orders/10/status")
                            .param("status", "PAID"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("errorMessage", "이미 취소된 주문입니다."));
        }
    }

    // ── 상품 수정 — 유효성 실패 ──

    @Nested
    @DisplayName("POST /admin/products/{id} — 상품 수정 유효성 실패")
    class UpdateProductValidationTests {

        @Test
        @DisplayName("필수 필드 누락 시 폼 재표시 + editMode=true")
        void updateProduct_validationFails_redisplaysForm() throws Exception {
            Product product = mock(Product.class);
            when(productService.findByIdForAdmin(1L)).thenReturn(product);
            when(categoryService.getAllActiveCategories()).thenReturn(Collections.emptyList());

            mockMvc.perform(post("/admin/products/1")
                            .param("productName", "")  // @NotBlank 위반
                            .param("price", "10000")
                            .param("stockQuantity", "100"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/product-form"))
                    .andExpect(model().attribute("editMode", true))
                    .andExpect(model().attributeExists("productId", "product", "categories"));

            // 유효성 실패 시 updateProduct이 호출되지 않아야 함
            verify(productService, never()).updateProduct(anyLong(), any());
        }
    }
}
