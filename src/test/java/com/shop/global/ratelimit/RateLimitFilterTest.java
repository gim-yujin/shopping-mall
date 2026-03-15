package com.shop.global.ratelimit;

import com.shop.global.security.ClientIpResolver;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RateLimitFilter 단위 테스트.
 *
 * <p>서블릿 필터를 직접 호출하여 429 응답, rate limit 헤더,
 * 인증/비인증 사용자 분기, 정적 리소스 제외를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ClientIpResolver clientIpResolver;

    private RateLimitService rateLimitService;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService();
        filter = new RateLimitFilter(rateLimitService, clientIpResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateUser() {
        CustomUserPrincipal principal = new CustomUserPrincipal(
                USER_ID, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @Nested
    @DisplayName("정상 요청 — 토큰 소비 성공")
    class AllowedRequests {

        @Test
        @DisplayName("API 요청 성공 시 rate limit 헤더가 포함된다")
        void addsRateLimitHeaders() throws Exception {
            authenticateUser();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            // READ 플랜 용량(60)에서 1개 소비 → 59 남음
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
            // 체인이 계속 진행되었으므로 상태 코드가 기본값(200)이어야 함
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("속도 초과 — 429 Too Many Requests")
    class RateLimitExceeded {

        @Test
        @DisplayName("API 토큰 소진 시 429 JSON 응답을 반환한다")
        void returns429JsonForApi() throws Exception {
            authenticateUser();

            // ORDER 플랜: 용량 5 → 5개 소비하여 소진
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
                filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            // 6번째 요청은 거부
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).contains("application/json");
            assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
            assertThat(response.getHeader("Retry-After")).isNotNull();
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        }

        @Test
        @DisplayName("SSR POST 토큰 소진 시 429 HTML 응답을 반환한다")
        void returns429HtmlForSsr() throws Exception {
            authenticateUser();

            // ORDER 플랜: 용량 5 → 소진
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("POST", "/orders");
                filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentType()).contains("text/html");
            assertThat(response.getContentAsString()).contains("요청이 너무 많습니다");
        }
    }

    @Nested
    @DisplayName("비인증 사용자 — IP 기반 제한")
    class AnonymousRequests {

        @Test
        @DisplayName("비인증 사용자는 IP 기반으로 속도 제한된다")
        void rateLimitsByIp() throws Exception {
            // 인증 컨텍스트 비어있음 → 비인증 사용자
            when(clientIpResolver.resolveClientIp(any())).thenReturn("192.168.1.100");

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("필터 제외 대상")
    class FilterExclusions {

        @Test
        @DisplayName("정적 리소스는 rate limit 필터를 건너뛴다")
        void skipsStaticResources() {
            MockHttpServletRequest cssRequest = new MockHttpServletRequest("GET", "/css/style.css");
            MockHttpServletRequest imgRequest = new MockHttpServletRequest("GET", "/images/logo.png");
            MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");

            assertThat(filter.shouldNotFilter(cssRequest)).isTrue();
            assertThat(filter.shouldNotFilter(imgRequest)).isTrue();
            assertThat(filter.shouldNotFilter(actuator)).isTrue();
        }

        @Test
        @DisplayName("API 요청은 rate limit 필터를 통과한다")
        void doesNotSkipApiRequests() {
            MockHttpServletRequest apiRequest = new MockHttpServletRequest("GET", "/api/v1/products");

            assertThat(filter.shouldNotFilter(apiRequest)).isFalse();
        }

        @Test
        @DisplayName("SSR GET 요청은 플랜이 null이므로 필터를 통과하되 rate limit은 미적용")
        void ssrGetPassesThroughWithoutRateLimit() throws Exception {
            authenticateUser();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products/1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            // rate limit 헤더가 없어야 함 — 플랜이 null이므로 토큰 소비 없이 통과
            assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("플랜별 독립성")
    class PlanIndependence {

        @Test
        @DisplayName("ORDER 한도를 소진해도 READ 한도에 영향 없다")
        void orderLimitDoesNotAffectRead() throws Exception {
            authenticateUser();

            // ORDER 플랜(용량 5) 소진
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
                filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            }

            // ORDER는 소진됨
            MockHttpServletRequest orderReq = new MockHttpServletRequest("POST", "/api/v1/orders");
            MockHttpServletResponse orderResp = new MockHttpServletResponse();
            filter.doFilterInternal(orderReq, orderResp, new MockFilterChain());
            assertThat(orderResp.getStatus()).isEqualTo(429);

            // READ는 독립적이므로 정상 통과
            MockHttpServletRequest readReq = new MockHttpServletRequest("GET", "/api/v1/products");
            MockHttpServletResponse readResp = new MockHttpServletResponse();
            filter.doFilterInternal(readReq, readResp, new MockFilterChain());
            assertThat(readResp.getStatus()).isEqualTo(200);
        }
    }
}
