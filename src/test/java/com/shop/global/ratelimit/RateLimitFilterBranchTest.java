package com.shop.global.ratelimit;

import com.shop.global.metrics.RateLimitMetrics;
import com.shop.global.security.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter 분기 커버리지 보강 테스트.
 *
 * <p>기존 RateLimitFilterTest에서 다루지 않은 분기를 검증한다:
 * - extractUserId: auth != null && isAuthenticated() 이지만
 *   principal이 CustomUserPrincipal이 아닌 경우 (String principal 등)
 * - extractUserId: auth가 인증되지 않은 경우
 * - shouldNotFilter: /static/, /js/, /favicon 경로</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterBranchTest {

    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private RateLimitMetrics rateLimitMetrics;

    private RateLimitService rateLimitService;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
        filter = new RateLimitFilter(rateLimitService, clientIpResolver, rateLimitMetrics);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── extractUserId: String principal (비 CustomUserPrincipal) ──

    @Test
    @DisplayName("principal이 String이면 userId=null → IP 기반 rate limit")
    void stringPrincipal_fallsBackToIp() throws Exception {
        // given: principal이 String "anonymousUser"
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null));
        org.mockito.Mockito.when(clientIpResolver.resolveClientIp(
                org.mockito.ArgumentMatchers.any())).thenReturn("1.2.3.4");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when: String principal → extractUserId() returns null → IP 기반
        filter.doFilterInternal(request, response, new MockFilterChain());

        // then: 정상 응답 (IP 기반 rate limit 적용)
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNotNull();
    }

    // ── shouldNotFilter: 추가 경로 ──

    @Test
    @DisplayName("/static/ 경로는 필터 제외")
    void staticPath_excluded() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/bundle.js");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("/js/ 경로는 필터 제외")
    void jsPath_excluded() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/js/app.js");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("/favicon.ico 경로는 필터 제외")
    void faviconPath_excluded() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/favicon.ico");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("SSR POST — DEFAULT 플랜 적용")
    void ssrPost_defaultPlan() throws Exception {
        // given: 비인증 사용자, SSR POST
        org.mockito.Mockito.when(clientIpResolver.resolveClientIp(
                org.mockito.ArgumentMatchers.any())).thenReturn("5.6.7.8");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cart/add");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilterInternal(request, response, new MockFilterChain());

        // then: DEFAULT 플랜(용량 30) 적용
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("30");
    }
}
