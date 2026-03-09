package com.shop.domain.coupon.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.global.exception.BusinessException;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CouponController 보충 단위 테스트.
 *
 * <p>기존 CouponControllerUnitTest는 GET /coupons(페이지 조회)만 커버한다.
 * 이 테스트는 나머지 POST 엔드포인트(쿠폰 코드 발급, 쿠폰 ID 발급)를 커버하여
 * CouponController의 전체 라인 커버리지를 달성한다.</p>
 *
 * <p>두 POST 메서드 모두 동일한 패턴(성공 → 리다이렉트+성공메시지, 실패 → 리다이렉트+에러메시지)이므로
 * 각각 성공/실패 케이스를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CouponControllerSupplementaryUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CouponService couponService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CouponController controller = new CouponController(couponService);
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

    // ── POST /coupons/issue (쿠폰 코드로 발급) ─────────────────

    @Test
    @DisplayName("쿠폰 코드 발급 성공 시 리다이렉트하고 성공 메시지를 전달한다")
    void issueCoupon_success() throws Exception {
        // given: 유효한 쿠폰 코드
        doNothing().when(couponService).issueCoupon(USER_ID, "SAVE10");

        // when & then
        mockMvc.perform(post("/coupons/issue")
                        .param("couponCode", "SAVE10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attribute("successMessage", "쿠폰이 발급되었습니다."));

        verify(couponService).issueCoupon(USER_ID, "SAVE10");
    }

    @Test
    @DisplayName("이미 발급받은 쿠폰 코드 입력 시 에러 메시지를 전달한다")
    void issueCoupon_alreadyIssued_error() throws Exception {
        // given: 이미 발급받은 쿠폰 → BusinessException
        doThrow(new BusinessException("COUPON_ALREADY_ISSUED", "이미 발급받은 쿠폰입니다."))
                .when(couponService).issueCoupon(USER_ID, "SAVE10");

        // when & then
        mockMvc.perform(post("/coupons/issue")
                        .param("couponCode", "SAVE10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attribute("errorMessage", "이미 발급받은 쿠폰입니다."));
    }

    // ── POST /coupons/issue/{couponId} (쿠폰 ID로 발급) ─────────

    @Test
    @DisplayName("쿠폰 ID 발급 성공 시 리다이렉트하고 성공 메시지를 전달한다")
    void issueCouponById_success() throws Exception {
        // given
        doNothing().when(couponService).issueCouponById(USER_ID, 5);

        // when & then
        mockMvc.perform(post("/coupons/issue/{couponId}", 5))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attribute("successMessage", "쿠폰이 발급되었습니다."));

        verify(couponService).issueCouponById(USER_ID, 5);
    }

    @Test
    @DisplayName("소진된 쿠폰 ID 발급 시 에러 메시지를 전달한다")
    void issueCouponById_exhausted_error() throws Exception {
        // given: 수량 소진 → BusinessException
        doThrow(new BusinessException("COUPON_EXHAUSTED", "쿠폰이 모두 소진되었습니다."))
                .when(couponService).issueCouponById(USER_ID, 5);

        // when & then
        mockMvc.perform(post("/coupons/issue/{couponId}", 5))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/coupons"))
                .andExpect(flash().attribute("errorMessage", "쿠폰이 모두 소진되었습니다."));
    }
}
