package com.shop.domain.user.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.security.CustomUserPrincipal;
import com.shop.global.exception.DuplicateConstraintMessageResolver;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MyPageControllerUnitTest {

    @Mock
    private UserService userService;
    @Mock
    private OrderService orderService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private CouponService couponService;
    @Mock
    private DuplicateConstraintMessageResolver duplicateConstraintMessageResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MyPageController controller = new MyPageController(userService, orderService, reviewService, couponService, duplicateConstraintMessageResolver);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

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

        when(userService.findById(1L)).thenReturn(new User("tester", "tester@example.com", "encoded", "테스터", "010-1111-2222"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("프로필 수정 검증 실패 - 빈 이름")
    void updateProfile_blankName_validationFail() throws Exception {
        mockMvc.perform(post("/mypage/profile")
                        .with(csrf())
                        .param("name", "")
                        .param("email", "valid@example.com")
                        .param("phone", "010-1234-5678"))
                .andExpect(status().isOk())
                .andExpect(view().name("mypage/profile"))
                .andExpect(model().attributeExists("profileErrorMessage"))
                .andExpect(model().attributeHasFieldErrors("profileUpdateRequest", "name"));

        verify(userService, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("프로필 수정 검증 실패 - 잘못된 이메일")
    void updateProfile_invalidEmail_validationFail() throws Exception {
        mockMvc.perform(post("/mypage/profile")
                        .with(csrf())
                        .param("name", "홍길동")
                        .param("email", "invalid-email")
                        .param("phone", "010-1234-5678"))
                .andExpect(status().isOk())
                .andExpect(view().name("mypage/profile"))
                .andExpect(model().attributeExists("profileErrorMessage"))
                .andExpect(model().attributeHasFieldErrors("profileUpdateRequest", "email"));

        verify(userService, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("비밀번호 변경 검증 실패 - 짧은 비밀번호")
    void changePassword_shortPassword_validationFail() throws Exception {
        mockMvc.perform(post("/mypage/password")
                        .with(csrf())
                        .param("currentPassword", "Current123!")
                        .param("newPassword", "Ab1!"))
                .andExpect(status().isOk())
                .andExpect(view().name("mypage/profile"))
                .andExpect(model().attributeExists("passwordErrorMessage"))
                .andExpect(model().attributeHasFieldErrors("passwordChangeRequest", "newPassword"));

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }
}
