package com.shop.domain.user.controller.api;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserApiController 단위 테스트.
 *
 * <p>사용자 REST API의 4개 엔드포인트를 검증한다:
 * POST /signup(회원가입, 공개), GET /me(프로필 조회),
 * PUT /me/profile(프로필 수정), POST /me/password(비밀번호 변경).</p>
 */
@ExtendWith(MockitoExtension.class)
class UserApiControllerUnitTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserApiController controller = new UserApiController(userService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication() {
        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private User createUserWithTier() {
        User user = new User("tester", "tester@test.com", "encodedPw", "테스터", "010-1234-5678");
        UserTier tier = mock(UserTier.class);
        when(tier.getTierName()).thenReturn("BRONZE");
        when(tier.getTierLevel()).thenReturn(1);
        user.setTier(tier);
        return user;
    }

    // ── POST /api/v1/users/signup — 회원가입 ──────────────────

    @Nested
    @DisplayName("POST /api/v1/users/signup — 회원가입")
    class SignupTests {

        @Test
        @DisplayName("유효한 요청으로 회원가입 성공 시 201을 반환한다")
        void signup_validRequest_returns201() throws Exception {
            User user = createUserWithTier();
            when(userService.signup(any())).thenReturn(user);

            mockMvc.perform(post("/api/v1/users/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "username": "newuser",
                                    "email": "new@test.com",
                                    "password": "Pass1234!",
                                    "name": "새사용자"
                                }
                                """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.username").value("tester"))
                    .andExpect(jsonPath("$.data.email").value("tester@test.com"));
        }

        @Test
        @DisplayName("아이디 누락 시 400 에러를 반환한다")
        void signup_blankUsername_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "username": "",
                                    "email": "new@test.com",
                                    "password": "Pass1234!",
                                    "name": "새사용자"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).signup(any());
        }

        @Test
        @DisplayName("이메일 형식 오류 시 400 에러를 반환한다")
        void signup_invalidEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "username": "newuser",
                                    "email": "not-an-email",
                                    "password": "Pass1234!",
                                    "name": "새사용자"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).signup(any());
        }

        @Test
        @DisplayName("비밀번호가 짧으면 400 에러를 반환한다")
        void signup_shortPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/users/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "username": "newuser",
                                    "email": "new@test.com",
                                    "password": "Ab1!",
                                    "name": "새사용자"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).signup(any());
        }

        @Test
        @DisplayName("중복 아이디로 가입 시 서비스에서 예외가 전파된다")
        void signup_duplicateUsername_throwsBusinessException() throws Exception {
            when(userService.signup(any()))
                    .thenThrow(new BusinessException("DUPLICATE", "이미 사용 중인 아이디입니다."));

            assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/users/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "username": "existing",
                                    "email": "new@test.com",
                                    "password": "Pass1234!",
                                    "name": "새사용자"
                                }
                                """)))
                    .hasCauseInstanceOf(BusinessException.class);
        }
    }

    // ── GET /api/v1/users/me — 프로필 조회 ────────────────────

    @Nested
    @DisplayName("GET /api/v1/users/me — 내 프로필 조회")
    class GetProfileTests {

        @Test
        @DisplayName("인증된 사용자의 프로필을 반환한다")
        void getMyProfile_authenticated_returnsProfile() throws Exception {
            setAuthentication();
            User user = createUserWithTier();
            when(userService.findById(USER_ID)).thenReturn(user);

            mockMvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.username").value("tester"))
                    .andExpect(jsonPath("$.data.email").value("tester@test.com"))
                    .andExpect(jsonPath("$.data.name").value("테스터"))
                    .andExpect(jsonPath("$.data.tierName").value("BRONZE"));
        }

        @Test
        @DisplayName("인증되지 않은 사용자는 예외가 발생한다")
        void getMyProfile_unauthenticated_throwsException() {
            assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/users/me")))
                    .hasCauseInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("존재하지 않는 사용자 ID로 조회 시 예외가 전파된다")
        void getMyProfile_userNotFound_throwsException() throws Exception {
            setAuthentication();
            when(userService.findById(USER_ID))
                    .thenThrow(new ResourceNotFoundException("사용자", USER_ID));

            assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/users/me")))
                    .hasCauseInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── PUT /api/v1/users/me/profile — 프로필 수정 ─────────────

    @Nested
    @DisplayName("PUT /api/v1/users/me/profile — 프로필 수정")
    class UpdateProfileTests {

        @Test
        @DisplayName("유효한 요청으로 프로필 수정 성공")
        void updateProfile_validRequest_returns200() throws Exception {
            setAuthentication();

            mockMvc.perform(put("/api/v1/users/me/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "name": "새이름",
                                    "email": "new@test.com",
                                    "phone": "010-9876-5432"
                                }
                                """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(userService).updateProfile(USER_ID, "새이름", "010-9876-5432", "new@test.com");
        }

        @Test
        @DisplayName("이름 누락 시 400 에러를 반환한다")
        void updateProfile_blankName_returns400() throws Exception {
            setAuthentication();

            mockMvc.perform(put("/api/v1/users/me/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "name": "",
                                    "email": "new@test.com",
                                    "phone": "010-9876-5432"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("이메일 형식 오류 시 400 에러를 반환한다")
        void updateProfile_invalidEmail_returns400() throws Exception {
            setAuthentication();

            mockMvc.perform(put("/api/v1/users/me/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "name": "테스터",
                                    "email": "invalid-email",
                                    "phone": "010-9876-5432"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
        }
    }

    // ── POST /api/v1/users/me/password — 비밀번호 변경 ─────────

    @Nested
    @DisplayName("POST /api/v1/users/me/password — 비밀번호 변경")
    class ChangePasswordTests {

        @Test
        @DisplayName("유효한 요청으로 비밀번호 변경 성공")
        void changePassword_validRequest_returns200() throws Exception {
            setAuthentication();

            mockMvc.perform(post("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "currentPassword": "OldPass1!",
                                    "newPassword": "NewPass1!"
                                }
                                """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(userService).changePassword(USER_ID, "OldPass1!", "NewPass1!");
        }

        @Test
        @DisplayName("현재 비밀번호 누락 시 400 에러를 반환한다")
        void changePassword_blankCurrentPassword_returns400() throws Exception {
            setAuthentication();

            mockMvc.perform(post("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "currentPassword": "",
                                    "newPassword": "NewPass1!"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("새 비밀번호가 정책 미달 시 400 에러를 반환한다")
        void changePassword_weakNewPassword_returns400() throws Exception {
            setAuthentication();

            mockMvc.perform(post("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "currentPassword": "OldPass1!",
                                    "newPassword": "weak"
                                }
                                """))
                    .andExpect(status().isBadRequest());

            verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("인증되지 않은 사용자는 예외가 발생한다")
        void changePassword_unauthenticated_throwsException() {
            assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/users/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {
                                    "currentPassword": "OldPass1!",
                                    "newPassword": "NewPass1!"
                                }
                                """)))
                    .hasCauseInstanceOf(NoSuchElementException.class);
        }
    }
}
