package com.shop.domain.coupon.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class CouponControllerUnitTest {

    @Mock
    private CouponService couponService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CouponController controller = new CouponController(couponService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        CustomUserPrincipal principal = new CustomUserPrincipal(
                1L,
                "tester",
                "encoded",
                "테스터",
                "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        when(couponService.getActiveCoupons(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(couponService.getUserCoupons(eq(1L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(couponService.getUserIssuedCouponIds(1L)).thenReturn(Set.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("myPage 파라미터에 따라 내 쿠폰 페이지 번호가 반영된다")
    void couponPage_myPageChangesMyCouponsPage() throws Exception {
        ArgumentCaptor<Pageable> myCouponsPageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/coupons")
                        .param("availablePage", "0")
                        .param("myPage", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("coupon/index"))
                .andExpect(model().attributeExists("myCoupons"));

        verify(couponService).getUserCoupons(eq(1L), myCouponsPageableCaptor.capture());
        assertThat(myCouponsPageableCaptor.getValue().getPageNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("myPage 음수 입력 시 내 쿠폰 페이지 번호는 0으로 보정된다")
    void couponPage_negativeMyPageNormalizedToZero() throws Exception {
        ArgumentCaptor<Pageable> myCouponsPageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/coupons")
                        .param("availablePage", "1")
                        .param("myPage", "-4"))
                .andExpect(status().isOk())
                .andExpect(view().name("coupon/index"));

        verify(couponService).getUserCoupons(eq(1L), myCouponsPageableCaptor.capture());
        assertThat(myCouponsPageableCaptor.getValue().getPageNumber()).isZero();
    }
}
