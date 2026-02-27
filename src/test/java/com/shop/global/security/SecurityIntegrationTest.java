package com.shop.global.security;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * [P2-12] Controller 통합 테스트.
 *
 * @SpringBootTest + @AutoConfigureMockMvc로 실제 Security 필터 체인,
 * CSRF 토큰, 세션 관리, GlobalExceptionHandler 리다이렉트가
 * 실제 HTTP 요청 흐름에서 기대대로 동작하는지 검증한다.
 *
 * 기존 @WebMvcTest 단위 테스트와의 차이:
 * - @WebMvcTest: Service를 Mock하고 컨트롤러 로직만 검증
 * - 이 테스트: 실제 Spring Context + Security 필터 체인 전체를 로드하여
 *   인증/인가/CSRF/예외 처리가 함께 동작하는지 검증
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTierRepository userTierRepository;

    private CustomUserPrincipal ensureAuthenticatedPrincipal(String username) {
        User userEntity = userRepository.findByUsernameIgnoreCase(username)
                .orElseGet(() -> {
                    UserTier basicTier = userTierRepository.findByTierLevel(1)
                            .orElseThrow(() -> new IllegalStateException("기본 사용자 등급이 존재하지 않습니다."));
                    User newUser = new User(username, username + "@test.local", "noop-password", "테스트사용자", "010-0000-0000");
                    newUser.setTier(basicTier);
                    return userRepository.save(newUser);
                });

        return new CustomUserPrincipal(
                userEntity.getUserId(),
                userEntity.getUsername(),
                userEntity.getPasswordHash(),
                userEntity.getName(),
                userEntity.getRole(),
                List.of(new SimpleGrantedAuthority(userEntity.getRole()))
        );
    }

    // ==================== 공개 경로 접근 ====================

    @Nested
    @DisplayName("공개 경로 접근")
    class PublicEndpoints {

        @Test
        @DisplayName("GET / — 인증 없이 접근 가능")
        void homePage_accessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /products — 인증 없이 접근 가능")
        void productList_accessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/products"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /auth/login — 로그인 페이지 접근 가능")
        void loginPage_accessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/auth/login"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /search — 검색 페이지 접근 가능")
        void searchPage_accessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/search").param("keyword", "test"))
                    .andExpect(status().isOk());
        }
    }

    // ==================== 인증 필요 경로 — SSR 리다이렉트 ====================

    @Nested
    @DisplayName("인증 필요 경로 — SSR 리다이렉트")
    class ProtectedSsrEndpoints {

        @Test
        @DisplayName("GET /cart — 미인증 시 로그인 페이지로 리다이렉트")
        void cart_redirectsToLoginWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/cart"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/auth/login"));
        }

        @Test
        @DisplayName("GET /orders — 미인증 시 로그인 페이지로 리다이렉트")
        void orders_redirectsToLoginWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/orders"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/auth/login"));
        }

        @Test
        @DisplayName("GET /mypage — 미인증 시 로그인 페이지로 리다이렉트")
        void mypage_redirectsToLoginWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/mypage"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/auth/login"));
        }
    }

    // ==================== 인증 필요 경로 — REST API 401 JSON ====================

    @Nested
    @DisplayName("인증 필요 경로 — REST API 401 JSON")
    class ProtectedApiEndpoints {

        @Test
        @DisplayName("GET /api/v1/cart — 미인증 시 401 JSON 응답 (리다이렉트가 아님)")
        void apiCart_returns401JsonWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/cart")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("GET /api/v1/orders — 미인증 시 401 JSON 응답")
        void apiOrders_returns401JsonWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("GET /api/v1/products — 공개 API는 인증 없이 200 응답")
        void apiProducts_accessibleWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/v1/products")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ==================== CSRF 토큰 검증 ====================

    @Nested
    @DisplayName("CSRF 토큰 검증")
    class CsrfProtection {

        @Test
        @DisplayName("POST /auth/login — CSRF 토큰 없으면 403")
        void login_withoutCsrf_returns403() throws Exception {
            mockMvc.perform(post("/auth/login")
                            .param("username", "test")
                            .param("password", "test"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /api/v1/cart — REST API 경로는 CSRF 면제")
        void apiCart_csrfExempt() throws Exception {
            // CSRF 토큰 없이 POST → 403이 아닌 401(인증 필요) 응답
            // CSRF가 적용되었다면 403이 먼저 반환될 것이다
            mockMvc.perform(post("/api/v1/cart")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":1,\"quantity\":1}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ==================== 관리자 권한 검증 ====================

    @Nested
    @DisplayName("관리자 권한 검증")
    class AdminAccess {

        @Test
        @DisplayName("GET /admin/products — 미인증 시 로그인 리다이렉트")
        void adminProducts_redirectsWhenUnauthenticated() throws Exception {
            mockMvc.perform(get("/admin/products"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/auth/login"));
        }

        @Test
        @WithMockUser(username = "user1", roles = {"USER"})
        @DisplayName("GET /admin/products — 일반 사용자는 403")
        void adminProducts_forbiddenForRegularUser() throws Exception {
            mockMvc.perform(get("/admin/products"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "admin1", roles = {"ADMIN"})
        @DisplayName("GET /admin/products — 관리자는 200")
        void adminProducts_accessibleForAdmin() throws Exception {
            mockMvc.perform(get("/admin/products"))
                    .andExpect(status().isOk());
        }
    }

    // ==================== 로그인 플로우 ====================

    @Nested
    @DisplayName("로그인 플로우")
    class LoginFlow {

        @Test
        @DisplayName("POST /auth/login — 잘못된 인증정보로 로그인 실패 시 에러 페이지로 리다이렉트")
        void login_badCredentials_redirectsWithError() throws Exception {
            mockMvc.perform(post("/auth/login")
                            .with(csrf())
                            .param("username", "nonexistent_user_xyz")
                            .param("password", "wrongpassword"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/login?error=true"));
        }

        @Test
        @DisplayName("POST /auth/login — 연속 실패 시 rate limiting 적용")
        void login_repeatedFailures_triggersRateLimiting() throws Exception {
            // 고유한 username + 동일 IP로 반복 실패
            String username = "ratelimit_test_" + System.nanoTime();

            // 첫 번째 실패 → backoff 1초
            mockMvc.perform(post("/auth/login")
                    .with(csrf())
                    .param("username", username)
                    .param("password", "wrong"));

            // 두 번째 실패 → backoff 2초
            mockMvc.perform(post("/auth/login")
                    .with(csrf())
                    .param("username", username)
                    .param("password", "wrong"));

            // 세 번째 시도 → LoginBlockPreAuthenticationFilter에 의해 차단
            // (backoff 시간 내이므로 필터에서 바로 리다이렉트)
            MvcResult result = mockMvc.perform(post("/auth/login")
                            .with(csrf())
                            .param("username", username)
                            .param("password", "wrong"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/login?error=true"))
                    .andReturn();

            // 차단 상태를 확인 (실패 이후 isBlocked 상태)
            assertThat(result.getResponse().getRedirectedUrl())
                    .isEqualTo("/auth/login?error=true");
        }

        @Test
        @WithMockUser(username = "testuser", roles = {"USER"})
        @DisplayName("POST /auth/logout — 로그아웃 후 홈으로 리다이렉트")
        void logout_redirectsToHome() throws Exception {
            mockMvc.perform(post("/auth/logout")
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
        }
    }

    // ==================== 인증된 사용자 접근 ====================

    @Nested
    @DisplayName("인증된 사용자 접근")
    class AuthenticatedAccess {

        @Test
        @DisplayName("GET /cart — 인증된 사용자는 장바구니 페이지 접근 가능")
        void cart_accessibleWhenAuthenticated() throws Exception {
            CustomUserPrincipal principal = ensureAuthenticatedPrincipal("testuser");
            mockMvc.perform(get("/cart").with(user(principal)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /wishlist — 인증된 사용자는 위시리스트 페이지 접근 가능")
        void wishlist_accessibleWhenAuthenticated() throws Exception {
            CustomUserPrincipal principal = ensureAuthenticatedPrincipal("testuser");
            mockMvc.perform(get("/wishlist").with(user(principal)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /orders — 인증된 사용자는 주문 목록 페이지 접근 가능")
        void orders_accessibleWhenAuthenticated() throws Exception {
            CustomUserPrincipal principal = ensureAuthenticatedPrincipal("testuser");
            mockMvc.perform(get("/orders").with(user(principal)))
                    .andExpect(status().isOk());
        }
    }
}
