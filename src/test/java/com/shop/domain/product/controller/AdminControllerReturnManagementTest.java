package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.dto.AdminReturnResponse;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.service.ProductService;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * [Step 5] AdminController 반품 관리 엔드포인트 단위 테스트.
 *
 * <h3>검증 범위</h3>
 *
 * <p>Step 5에서 추가된 세 가지 엔드포인트와 대시보드 반품 카드를 검증한다.</p>
 *
 * <ul>
 *   <li>GET /admin/returns — 반품 대기 목록 조회, 뷰 이름, 모델 속성</li>
 *   <li>POST /admin/returns/{id}/approve — 성공 시 리다이렉트 + 성공 메시지,
 *       실패 시 리다이렉트 + 오류 메시지</li>
 *   <li>POST /admin/returns/{id}/reject — 성공/실패 동작, 빈 거절 사유 기본값 처리</li>
 *   <li>GET /admin — 대시보드에 pendingReturnCount 모델 속성 포함</li>
 * </ul>
 *
 * <p>서비스 호출은 Mock으로 대체하고, 컨트롤러의 요청 매핑·파라미터 바인딩·
 * 에러 처리·리다이렉트 로직만 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerReturnManagementTest {

    @Mock private ProductService productService;
    @Mock private OrderService orderService;
    @Mock private CategoryService categoryService;
    @Mock private CouponService couponService;

    @InjectMocks
    private AdminController adminController;

    // ──────────── GET /admin/returns ────────────

    @Nested
    @DisplayName("GET /admin/returns — 반품 대기 목록")
    class ReturnListTests {

        @Test
        @DisplayName("정상 조회 — 뷰 이름 'admin/returns', 모델에 returns 포함")
        void returnList_returnsViewWithReturnsAttribute() {
            // Given
            AdminReturnResponse item = new AdminReturnResponse(
                    1L, "ORD-TEST-001", 10L, "테스트 상품", 2,
                    "DEFECT", LocalDateTime.now(), "testuser", "test@test.com");
            Page<AdminReturnResponse> page = new PageImpl<>(List.of(item));
            when(orderService.getReturnRequests(0)).thenReturn(page);

            Model model = new ConcurrentModel();

            // When
            String view = adminController.returnList(0, model);

            // Then
            assertThat(view).isEqualTo("admin/returns");
            assertThat(model.containsAttribute("returns")).isTrue();

            @SuppressWarnings("unchecked")
            Page<AdminReturnResponse> result = (Page<AdminReturnResponse>) model.getAttribute("returns");
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).productName()).isEqualTo("테스트 상품");
        }

        @Test
        @DisplayName("빈 목록 — totalElements = 0")
        void returnList_emptyPage() {
            when(orderService.getReturnRequests(0)).thenReturn(Page.empty());
            Model model = new ConcurrentModel();

            String view = adminController.returnList(0, model);

            assertThat(view).isEqualTo("admin/returns");
            @SuppressWarnings("unchecked")
            Page<AdminReturnResponse> result = (Page<AdminReturnResponse>) model.getAttribute("returns");
            assertThat(result).isNotNull();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("음수 페이지 번호 — PagingParams.normalizePage로 0으로 보정")
        void returnList_negativePageNormalizedToZero() {
            when(orderService.getReturnRequests(0)).thenReturn(Page.empty());
            Model model = new ConcurrentModel();

            // normalizePage(-1) → 0
            adminController.returnList(-1, model);

            verify(orderService).getReturnRequests(0);
        }
    }

    // ──────────── POST /admin/returns/{id}/approve ────────────

    @Nested
    @DisplayName("POST /admin/returns/{id}/approve — 반품 승인")
    class ApproveReturnTests {

        @Test
        @DisplayName("승인 성공 — 리다이렉트 + 성공 메시지")
        void approveReturn_success() {
            // Given
            doNothing().when(orderService).approveReturn(1L, 10L);
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            // When
            String view = adminController.approveReturn(10L, 1L, redirect);

            // Then
            assertThat(view).isEqualTo("redirect:/admin/returns");
            assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
            assertThat(redirect.getFlashAttributes().get("successMessage").toString())
                    .contains("승인");
            verify(orderService).approveReturn(1L, 10L);
        }

        @Test
        @DisplayName("승인 실패 (잘못된 상태) — 리다이렉트 + 오류 메시지")
        void approveReturn_invalidStatus_showsError() {
            // Given: 이미 거절된 아이템에 대해 승인 시도
            doThrow(new BusinessException("INVALID_ITEM_STATUS",
                    "반품 신청 상태의 아이템만 승인할 수 있습니다."))
                    .when(orderService).approveReturn(1L, 10L);
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            // When
            String view = adminController.approveReturn(10L, 1L, redirect);

            // Then: 오류가 발생해도 리다이렉트는 동일
            assertThat(view).isEqualTo("redirect:/admin/returns");
            assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
            assertThat(redirect.getFlashAttributes().get("errorMessage").toString())
                    .contains("반품 신청 상태");
        }
    }

    // ──────────── POST /admin/returns/{id}/reject ────────────

    @Nested
    @DisplayName("POST /admin/returns/{id}/reject — 반품 거절")
    class RejectReturnTests {

        @Test
        @DisplayName("거절 성공 — 거절 사유 전달, 리다이렉트 + 성공 메시지")
        void rejectReturn_success() {
            // Given
            doNothing().when(orderService).rejectReturn(1L, 10L, "상품 사용 흔적 확인");
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            // When
            String view = adminController.rejectReturn(10L, 1L, "상품 사용 흔적 확인", redirect);

            // Then
            assertThat(view).isEqualTo("redirect:/admin/returns");
            assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
            verify(orderService).rejectReturn(1L, 10L, "상품 사용 흔적 확인");
        }

        @Test
        @DisplayName("빈 거절 사유 — 기본 메시지로 대체")
        void rejectReturn_blankReason_usesDefault() {
            // Given: 빈 문자열 거절 사유
            doNothing().when(orderService).rejectReturn(eq(1L), eq(10L), anyString());
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            // When
            adminController.rejectReturn(10L, 1L, "  ", redirect);

            // Then: 기본 메시지 "반품 요건을 충족하지 않습니다."로 대체되어 전달
            verify(orderService).rejectReturn(1L, 10L, "반품 요건을 충족하지 않습니다.");
        }

        @Test
        @DisplayName("null 거절 사유 — 기본 메시지로 대체")
        void rejectReturn_nullReason_usesDefault() {
            doNothing().when(orderService).rejectReturn(eq(1L), eq(10L), anyString());
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            adminController.rejectReturn(10L, 1L, null, redirect);

            verify(orderService).rejectReturn(1L, 10L, "반품 요건을 충족하지 않습니다.");
        }

        @Test
        @DisplayName("거절 실패 — 리다이렉트 + 오류 메시지")
        void rejectReturn_failure_showsError() {
            doThrow(new BusinessException("INVALID_ITEM_STATUS_TRANSITION",
                    "아이템 상태를 변경할 수 없습니다."))
                    .when(orderService).rejectReturn(1L, 10L, "사유");
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            String view = adminController.rejectReturn(10L, 1L, "사유", redirect);

            assertThat(view).isEqualTo("redirect:/admin/returns");
            assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
        }

        @Test
        @DisplayName("거절 사유 앞뒤 공백 제거")
        void rejectReturn_trimsWhitespace() {
            doNothing().when(orderService).rejectReturn(eq(1L), eq(10L), anyString());
            RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

            adminController.rejectReturn(10L, 1L, "  사용 흔적 확인  ", redirect);

            verify(orderService).rejectReturn(1L, 10L, "사용 흔적 확인");
        }
    }

    // ──────────── GET /admin — 대시보드 반품 카드 ────────────

    @Nested
    @DisplayName("GET /admin — 대시보드 반품 대기 건수")
    class DashboardReturnCardTests {

        @Test
        @DisplayName("대시보드에 pendingReturnCount 모델 속성 포함")
        void dashboard_includesPendingReturnCount() {
            // Given
            when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of()));
            when(orderService.getAllOrders(any())).thenReturn(new PageImpl<>(List.of()));
            when(couponService.getCouponStats()).thenReturn(new CouponStats(0, 0, 0, 0));
            when(orderService.getPendingReturnCount()).thenReturn(5L);

            Model model = new ConcurrentModel();

            // When
            String view = adminController.dashboard(model);

            // Then
            assertThat(view).isEqualTo("admin/dashboard");
            assertThat(model.getAttribute("pendingReturnCount")).isEqualTo(5L);
            verify(orderService).getPendingReturnCount();
        }

        @Test
        @DisplayName("반품 대기 0건 — pendingReturnCount = 0")
        void dashboard_zeroPendingReturns() {
            when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of()));
            when(orderService.getAllOrders(any())).thenReturn(new PageImpl<>(List.of()));
            when(couponService.getCouponStats()).thenReturn(new CouponStats(0, 0, 0, 0));
            when(orderService.getPendingReturnCount()).thenReturn(0L);

            Model model = new ConcurrentModel();
            adminController.dashboard(model);

            assertThat(model.getAttribute("pendingReturnCount")).isEqualTo(0L);
        }
    }
}
