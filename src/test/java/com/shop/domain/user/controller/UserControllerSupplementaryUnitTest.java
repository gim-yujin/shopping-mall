package com.shop.domain.user.controller;

import com.shop.domain.coupon.service.CouponService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.point.service.PointQueryService;
import com.shop.domain.review.service.ReviewService;
import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.service.UserService;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.DuplicateConstraintMessageResolver;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * user.controller 패키지 보충 단위 테스트.
 *
 * <p>기존 MyPageControllerUnitTest는 프로필 수정/비밀번호 변경 검증 실패 + 포인트 조회만 커버한다.
 * 이 테스트는 나머지 미커버 경로를 전부 메운다:</p>
 * <ul>
 *   <li>AuthController: 로그인 폼, 회원가입 폼, 회원가입 처리 (성공/검증실패/BusinessException/DataIntegrityViolation)</li>
 *   <li>MyPageController: 대시보드, 프로필 폼, 프로필 수정 성공/BusinessException/DataIntegrityViolation,
 *       비밀번호 변경 성공/BusinessException, 내 리뷰 목록</li>
 * </ul>
 *
 * <p>커버리지 목표: 42% → 90%+ (79라인 중 대부분 커버)</p>
 */
@ExtendWith(MockitoExtension.class)
class UserControllerSupplementaryUnitTest {

    private static final Long USER_ID = 1L;

    // ── AuthController 의존성 ───────────────────────────────────
    @Mock private UserService userService;
    @Mock private DuplicateConstraintMessageResolver duplicateResolver;

    // ── MyPageController 의존성 ─────────────────────────────────
    @Mock private OrderService orderService;
    @Mock private ReviewService reviewService;
    @Mock private CouponService couponService;
    @Mock private PointQueryService pointQueryService;

    private MockMvc authMvc;
    private MockMvc myPageMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // AuthController는 인증 불필요 (로그인/회원가입 폼)
        AuthController authController = new AuthController(userService, duplicateResolver);
        authMvc = MockMvcBuilders.standaloneSetup(authController)
                .setValidator(validator)
                .build();

        // MyPageController는 인증 필요
        MyPageController myPageController = new MyPageController(
                userService, orderService, reviewService,
                couponService, pointQueryService, duplicateResolver);
        myPageMvc = MockMvcBuilders.standaloneSetup(myPageController)
                .setValidator(validator)
                .build();

        // SecurityUtil.getCurrentUserId()가 동작하도록 인증 컨텍스트 설정
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

    private User createUser() {
        return new User("tester", "tester@example.com", "encoded", "테스터", "010-1111-2222");
    }

    // ═══════════════════════════════════════════════════════════
    // AuthController 테스트
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AuthController")
    class AuthControllerTests {

        @Test
        @DisplayName("GET /auth/login — 로그인 폼을 렌더링한다")
        void loginPage() throws Exception {
            authMvc.perform(get("/auth/login"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/login"));
        }

        @Test
        @DisplayName("GET /auth/signup — 회원가입 폼을 렌더링하고 빈 SignupRequest를 설정한다")
        void signupPage() throws Exception {
            authMvc.perform(get("/auth/signup"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/signup"))
                    .andExpect(model().attributeExists("signupRequest"));
        }

        @Test
        @DisplayName("POST /auth/signup — 정상 가입 시 로그인 페이지로 리다이렉트한다")
        void signup_success() throws Exception {
            // given
            when(userService.signup(any(SignupRequest.class))).thenReturn(createUser());

            // when & then
            authMvc.perform(post("/auth/signup")
                            .param("username", "newuser")
                            .param("email", "new@example.com")
                            .param("password", "Password1!")
                            .param("name", "신규유저")
                            .param("phone", "010-9999-8888"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/login"))
                    .andExpect(flash().attribute("successMessage", "회원가입이 완료되었습니다. 로그인해주세요."));
        }

        @Test
        @DisplayName("POST /auth/signup — 검증 실패 시 회원가입 폼을 다시 보여준다")
        void signup_validationError() throws Exception {
            // given: username 빈 문자열 → @NotBlank 위반
            authMvc.perform(post("/auth/signup")
                            .param("username", "")
                            .param("email", "new@example.com")
                            .param("password", "Password1!")
                            .param("name", "신규유저"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/signup"));

            verify(userService, never()).signup(any());
        }

        @Test
        @DisplayName("POST /auth/signup — BusinessException 시 에러 메시지와 함께 리다이렉트한다")
        void signup_businessException() throws Exception {
            // given: 아이디 중복 등 비즈니스 예외
            when(userService.signup(any(SignupRequest.class)))
                    .thenThrow(new BusinessException("DUPLICATE_USERNAME", "이미 사용 중인 아이디입니다."));

            authMvc.perform(post("/auth/signup")
                            .param("username", "existing")
                            .param("email", "new@example.com")
                            .param("password", "Password1!")
                            .param("name", "신규유저"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/signup"))
                    .andExpect(flash().attribute("errorMessage", "이미 사용 중인 아이디입니다."));
        }

        @Test
        @DisplayName("POST /auth/signup — DataIntegrityViolationException 시 중복 메시지를 해석하여 전달한다")
        void signup_dataIntegrityViolation() throws Exception {
            // given: DB 유니크 제약 위반 → DuplicateConstraintMessageResolver가 메시지 해석
            when(userService.signup(any(SignupRequest.class)))
                    .thenThrow(new DataIntegrityViolationException("unique constraint"));
            when(duplicateResolver.resolve(any(DataIntegrityViolationException.class)))
                    .thenReturn("이미 등록된 이메일입니다.");

            authMvc.perform(post("/auth/signup")
                            .param("username", "newuser")
                            .param("email", "dup@example.com")
                            .param("password", "Password1!")
                            .param("name", "신규유저"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/signup"))
                    .andExpect(flash().attribute("errorMessage", "이미 등록된 이메일입니다."));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // MyPageController 보충 테스트
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MyPageController — 대시보드 / 프로필 폼 / 리뷰 목록")
    class MyPageSupplementaryTests {

        @Test
        @DisplayName("GET /mypage — 대시보드에 사용자 정보, 최근 주문, 쿠폰을 표시한다")
        void myPage_dashboard() throws Exception {
            // given
            when(userService.findById(USER_ID)).thenReturn(createUser());
            when(orderService.getOrdersByUser(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(couponService.getAvailableCoupons(USER_ID)).thenReturn(Collections.emptyList());

            // when & then
            myPageMvc.perform(get("/mypage"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("mypage/index"))
                    .andExpect(model().attributeExists("user", "recentOrders", "coupons"));
        }

        @Test
        @DisplayName("GET /mypage/profile — 프로필 폼에 기존 사용자 정보가 채워진다")
        void profilePage() throws Exception {
            // given
            when(userService.findById(USER_ID)).thenReturn(createUser());

            // when & then: profileUpdateRequest와 passwordChangeRequest가 모델에 설정
            myPageMvc.perform(get("/mypage/profile"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("mypage/profile"))
                    .andExpect(model().attributeExists("user", "profileUpdateRequest", "passwordChangeRequest"));
        }

        @Test
        @DisplayName("GET /mypage/reviews — 내 리뷰 목록을 조회한다")
        void myReviews() throws Exception {
            // given
            when(reviewService.getUserReviews(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // when & then
            myPageMvc.perform(get("/mypage/reviews").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("mypage/reviews"))
                    .andExpect(model().attributeExists("reviews"));
        }
    }

    @Nested
    @DisplayName("MyPageController — 프로필 수정 성공 경로")
    class ProfileUpdateSuccessTests {

        @Test
        @DisplayName("POST /mypage/profile — 정상 수정 시 성공 메시지와 함께 리다이렉트한다")
        void updateProfile_success() throws Exception {
            // given
            doNothing().when(userService).updateProfile(USER_ID, "홍길동", "010-1234-5678", "hong@example.com");

            // when & then
            myPageMvc.perform(post("/mypage/profile")
                            .param("name", "홍길동")
                            .param("email", "hong@example.com")
                            .param("phone", "010-1234-5678"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mypage/profile"))
                    .andExpect(flash().attribute("successMessage", "프로필이 수정되었습니다."));
        }

        @Test
        @DisplayName("POST /mypage/profile — BusinessException 시 에러 메시지를 전달한다")
        void updateProfile_businessException() throws Exception {
            // given
            doThrow(new BusinessException("PROFILE_ERROR", "프로필 수정에 실패했습니다."))
                    .when(userService).updateProfile(anyLong(), anyString(), anyString(), anyString());

            // when & then
            myPageMvc.perform(post("/mypage/profile")
                            .param("name", "홍길동")
                            .param("email", "hong@example.com")
                            .param("phone", "010-1234-5678"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mypage/profile"))
                    .andExpect(flash().attribute("profileErrorMessage", "프로필 수정에 실패했습니다."));
        }

        @Test
        @DisplayName("POST /mypage/profile — DataIntegrityViolationException 시 중복 메시지를 해석하여 전달한다")
        void updateProfile_dataIntegrityViolation() throws Exception {
            // given: 이메일 유니크 제약 위반
            doThrow(new DataIntegrityViolationException("unique constraint"))
                    .when(userService).updateProfile(anyLong(), anyString(), anyString(), anyString());
            when(duplicateResolver.resolve(any(DataIntegrityViolationException.class)))
                    .thenReturn("이미 사용 중인 이메일입니다.");

            // when & then
            myPageMvc.perform(post("/mypage/profile")
                            .param("name", "홍길동")
                            .param("email", "dup@example.com")
                            .param("phone", "010-1234-5678"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mypage/profile"))
                    .andExpect(flash().attribute("profileErrorMessage", "이미 사용 중인 이메일입니다."));
        }
    }

    @Nested
    @DisplayName("MyPageController — 비밀번호 변경 성공 경로")
    class PasswordChangeSuccessTests {

        @Test
        @DisplayName("POST /mypage/password — 정상 변경 시 성공 메시지와 함께 리다이렉트한다")
        void changePassword_success() throws Exception {
            // given
            doNothing().when(userService).changePassword(USER_ID, "OldPass1!", "NewPass1!");

            // when & then
            myPageMvc.perform(post("/mypage/password")
                            .param("currentPassword", "OldPass1!")
                            .param("newPassword", "NewPass1!"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mypage/profile"))
                    .andExpect(flash().attribute("successMessage", "비밀번호가 변경되었습니다."));
        }

        @Test
        @DisplayName("POST /mypage/password — 현재 비밀번호 불일치 시 에러 메시지를 전달한다")
        void changePassword_businessException() throws Exception {
            // given: 현재 비밀번호 틀림
            doThrow(new BusinessException("WRONG_PASSWORD", "현재 비밀번호가 일치하지 않습니다."))
                    .when(userService).changePassword(eq(USER_ID), anyString(), anyString());

            // when & then
            myPageMvc.perform(post("/mypage/password")
                            .param("currentPassword", "WrongPass1!")
                            .param("newPassword", "NewPass1!"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/mypage/profile"))
                    .andExpect(flash().attribute("passwordErrorMessage", "현재 비밀번호가 일치하지 않습니다."));
        }
    }
}
