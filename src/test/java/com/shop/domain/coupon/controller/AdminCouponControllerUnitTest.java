package com.shop.domain.coupon.controller;

import com.shop.domain.coupon.dto.AdminCouponRequest;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.DiscountType;
import com.shop.domain.coupon.service.CouponService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminCouponController 단위 테스트.
 *
 * <p>관리자 쿠폰 CRUD의 6개 엔드포인트를 커버한다:
 * 목록 조회, 등록 폼, 등록 처리, 수정 폼, 수정 처리, 활성/비활성 토글.</p>
 *
 * <p>관리자 권한 검증은 SecurityConfig + SecurityIntegrationTest에서 담당하므로,
 * 여기서는 ADMIN 권한을 가진 사용자로 SecurityContext를 구성하고
 * 컨트롤러 로직(바인딩, 검증, 서비스 호출, 뷰 반환)에 집중한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminCouponControllerUnitTest {

    private static final Long ADMIN_USER_ID = 99L;

    @Mock
    private CouponService couponService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminCouponController controller = new AdminCouponController(couponService);

        // @Valid 어노테이션이 AdminCouponRequest에 적용되어 있으므로 Validator 등록 필요
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        // 관리자 권한으로 SecurityContext 설정
        CustomUserPrincipal principal = new CustomUserPrincipal(
                ADMIN_USER_ID, "admin", "encoded", "관리자", "ROLE_ADMIN",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
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
     * 테스트용 Coupon 엔티티를 생성한다.
     * AdminCouponController의 수정 폼(editCouponForm)에서
     * Coupon의 getter를 호출하여 AdminCouponRequest에 값을 채우므로
     * 모든 필드가 설정되어야 한다.
     */
    private Coupon createCoupon() {
        return new Coupon(
                "SUMMER2025", "여름 할인", DiscountType.PERCENT,
                new BigDecimal("15"), new BigDecimal("30000"), new BigDecimal("10000"),
                100, LocalDateTime.of(2025, 6, 1, 0, 0), LocalDateTime.of(2025, 8, 31, 23, 59)
        );
    }

    // ── GET /admin/coupons ──────────────────────────────────────

    @Nested
    @DisplayName("GET /admin/coupons — 쿠폰 목록")
    class CouponListTests {

        @Test
        @DisplayName("쿠폰 목록 페이지를 렌더링한다")
        void couponList_rendersListView() throws Exception {
            // given
            when(couponService.getAllCouponsForAdmin(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(createCoupon()), PageRequest.of(0, 20), 1));

            // when & then
            mockMvc.perform(get("/admin/coupons").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupons"))
                    .andExpect(model().attributeExists("coupons"));
        }
    }

    // ── GET /admin/coupons/new ──────────────────────────────────

    @Nested
    @DisplayName("GET /admin/coupons/new — 등록 폼")
    class NewCouponFormTests {

        @Test
        @DisplayName("등록 폼에 빈 요청 객체와 할인유형 목록을 설정한다")
        void newCouponForm_rendersFormView() throws Exception {
            // when & then: editMode=false, discountTypes 포함
            mockMvc.perform(get("/admin/coupons/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attributeExists("request", "discountTypes"))
                    .andExpect(model().attribute("editMode", false));
        }
    }

    // ── POST /admin/coupons ─────────────────────────────────────

    @Nested
    @DisplayName("POST /admin/coupons — 쿠폰 등록")
    class CreateCouponTests {

        @Test
        @DisplayName("정상 등록 시 목록으로 리다이렉트하고 성공 메시지를 전달한다")
        void createCoupon_success() throws Exception {
            // given
            Coupon coupon = createCoupon();
            when(couponService.createCoupon(any(AdminCouponRequest.class))).thenReturn(coupon);

            // when & then: 필수 필드를 모두 전달
            mockMvc.perform(post("/admin/coupons")
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "여름 할인")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "15")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "100")
                            .param("validFrom", "2025-06-01T00:00")
                            .param("validUntil", "2025-08-31T23:59"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/coupons"))
                    .andExpect(flash().attributeExists("successMessage"));
        }

        @Test
        @DisplayName("필수 필드 누락 시 폼을 다시 보여준다")
        void createCoupon_validationError_returnsForm() throws Exception {
            // given: couponName 누락 → @NotBlank 위반
            // when & then: 검증 실패 → admin/coupon-form 뷰 반환 (리다이렉트가 아님)
            mockMvc.perform(post("/admin/coupons")
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "15")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "100"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attribute("editMode", false));

            verify(couponService, never()).createCoupon(any());
        }

        @Test
        @DisplayName("중복 쿠폰 코드 시 폼을 다시 보여주고 에러 메시지를 설정한다")
        void createCoupon_businessException_returnsFormWithError() throws Exception {
            // given: 중복 쿠폰 코드 → BusinessException
            when(couponService.createCoupon(any(AdminCouponRequest.class)))
                    .thenThrow(new BusinessException("DUPLICATE_COUPON_CODE", "이미 존재하는 쿠폰 코드입니다."));

            // when & then
            mockMvc.perform(post("/admin/coupons")
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "여름 할인")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "15")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "100")
                            .param("validFrom", "2025-06-01T00:00")
                            .param("validUntil", "2025-08-31T23:59"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attribute("errorMessage", "이미 존재하는 쿠폰 코드입니다."));
        }
    }

    // ── GET /admin/coupons/{couponId}/edit ───────────────────────

    @Nested
    @DisplayName("GET /admin/coupons/{couponId}/edit — 수정 폼")
    class EditCouponFormTests {

        @Test
        @DisplayName("수정 폼에 기존 쿠폰 정보가 채워진다")
        void editCouponForm_populatesExistingData() throws Exception {
            // given: 기존 쿠폰 조회
            Coupon coupon = createCoupon();
            when(couponService.findByIdForAdmin(5)).thenReturn(coupon);

            // when & then: editMode=true, 쿠폰 데이터가 request 모델에 포함
            mockMvc.perform(get("/admin/coupons/{couponId}/edit", 5))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attribute("editMode", true))
                    .andExpect(model().attribute("couponId", 5))
                    .andExpect(model().attributeExists("request", "coupon", "discountTypes"));
        }
    }

    // ── POST /admin/coupons/{couponId} ──────────────────────────

    @Nested
    @DisplayName("POST /admin/coupons/{couponId} — 쿠폰 수정")
    class UpdateCouponTests {

        @Test
        @DisplayName("정상 수정 시 목록으로 리다이렉트하고 성공 메시지를 전달한다")
        void updateCoupon_success() throws Exception {
            // given
            Coupon coupon = createCoupon();
            when(couponService.updateCoupon(eq(5), any(AdminCouponRequest.class))).thenReturn(coupon);

            // when & then
            mockMvc.perform(post("/admin/coupons/{couponId}", 5)
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "여름 할인 수정")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "20")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "200")
                            .param("validFrom", "2025-06-01T00:00")
                            .param("validUntil", "2025-08-31T23:59"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/coupons"))
                    .andExpect(flash().attributeExists("successMessage"));
        }

        @Test
        @DisplayName("검증 실패 시 수정 폼을 다시 보여준다")
        void updateCoupon_validationError_returnsForm() throws Exception {
            // given: couponName 빈 문자열 → 검증 실패
            // findByIdForAdmin은 검증 실패 시 폼을 다시 보여줄 때 호출됨
            Coupon coupon = createCoupon();
            when(couponService.findByIdForAdmin(5)).thenReturn(coupon);

            // when & then
            mockMvc.perform(post("/admin/coupons/{couponId}", 5)
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "20")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "200"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attribute("editMode", true))
                    .andExpect(model().attribute("couponId", 5));

            verify(couponService, never()).updateCoupon(anyInt(), any());
        }

        @Test
        @DisplayName("비즈니스 예외 시 수정 폼을 다시 보여주고 에러 메시지를 설정한다")
        void updateCoupon_businessException() throws Exception {
            // given
            Coupon coupon = createCoupon();
            when(couponService.updateCoupon(eq(5), any(AdminCouponRequest.class)))
                    .thenThrow(new BusinessException("COUPON_UPDATE_ERROR", "수정할 수 없습니다."));
            when(couponService.findByIdForAdmin(5)).thenReturn(coupon);

            // when & then
            mockMvc.perform(post("/admin/coupons/{couponId}", 5)
                            .param("couponCode", "SUMMER2025")
                            .param("couponName", "여름 할인")
                            .param("discountType", "PERCENT")
                            .param("discountValue", "20")
                            .param("minOrderAmount", "30000")
                            .param("totalQuantity", "200")
                            .param("validFrom", "2025-06-01T00:00")
                            .param("validUntil", "2025-08-31T23:59"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/coupon-form"))
                    .andExpect(model().attribute("errorMessage", "수정할 수 없습니다."));
        }
    }

    // ── POST /admin/coupons/{couponId}/toggle-active ────────────

    @Nested
    @DisplayName("POST /admin/coupons/{couponId}/toggle-active — 활성/비활성 토글")
    class ToggleActiveTests {

        @Test
        @DisplayName("토글 성공 시 목록으로 리다이렉트하고 성공 메시지를 전달한다")
        void toggleActive_success() throws Exception {
            // given
            doNothing().when(couponService).toggleCouponActive(5);

            // when & then
            mockMvc.perform(post("/admin/coupons/{couponId}/toggle-active", 5))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/coupons"))
                    .andExpect(flash().attribute("successMessage", "쿠폰 상태가 변경되었습니다."));

            verify(couponService).toggleCouponActive(5);
        }
    }
}
