package com.shop.global.ratelimit;

import com.shop.global.metrics.RateLimitMetrics;
import com.shop.global.security.ClientIpResolver;
import com.shop.global.security.CustomUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API 속도 제한 필터.
 *
 * <h3>동작 원리</h3>
 * <p>모든 요청에 대해 {@link RateLimitPlanResolver}로 적용할 플랜을 결정하고,
 * {@link RateLimitService}에서 토큰 소비를 시도한다.
 * 토큰이 소진되면 HTTP 429 Too Many Requests를 반환한다.</p>
 *
 * <h3>필터 체인 위치</h3>
 * <p>SecurityConfig에서 {@code UsernamePasswordAuthenticationFilter} 이후에 등록하여
 * SecurityContext에 인증 정보가 설정된 상태에서 userId를 추출할 수 있도록 한다.
 * 인증 전에 실행되면 모든 요청이 IP 기반으로 제한되어 정확도가 떨어진다.</p>
 *
 * <h3>응답 헤더 (RFC 6585 + draft-ietf-httpapi-ratelimit-headers)</h3>
 * <ul>
 *   <li>{@code X-RateLimit-Limit}: 윈도우당 최대 요청 수</li>
 *   <li>{@code X-RateLimit-Remaining}: 남은 요청 수</li>
 *   <li>{@code Retry-After}: 429 시 재시도까지 대기 시간 (초)</li>
 * </ul>
 *
 * <h3>정적 리소스 제외</h3>
 * <p>CSS, JS, 이미지 등 정적 리소스는 rate limit 대상에서 제외한다.
 * 이들은 CDN이나 브라우저 캐시로 처리되며, rate limit을 걸면
 * 페이지 렌더링에 영향을 줄 수 있다.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitMetrics rateLimitMetrics;

    public RateLimitFilter(RateLimitService rateLimitService, ClientIpResolver clientIpResolver,
                           RateLimitMetrics rateLimitMetrics) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.rateLimitMetrics = rateLimitMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RateLimitPlan plan = RateLimitPlanResolver.resolve(request);

        // rate limit 미적용 요청은 그대로 통과
        if (plan == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 인증 여부에 따라 userId 또는 IP 기반으로 토큰 소비
        TokenBucket.ConsumeResult result = consumeToken(request, plan);

        // 성공: rate limit 헤더를 추가하고 요청 진행
        if (result.allowed()) {
            // [Phase 13] 허용된 요청을 플랜별로 카운팅 — Prometheus에서 플랜별 트래픽 분포 확인 가능
            rateLimitMetrics.recordAllowed(plan);
            addRateLimitHeaders(response, plan, result);
            filterChain.doFilter(request, response);
            return;
        }

        // 실패: 429 Too Many Requests 반환
        // [Phase 13] 거부된 요청을 플랜별로 카운팅 — ORDER 거부 급증 시 봇 공격 의심 가능
        rateLimitMetrics.recordRejected(plan);
        handleRateLimitExceeded(request, response, plan, result);
    }

    /**
     * 정적 리소스는 rate limit 대상에서 제외한다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/static/")
                || uri.startsWith("/css/")
                || uri.startsWith("/images/")
                || uri.startsWith("/js/")
                || uri.startsWith("/favicon")
                || uri.startsWith("/actuator/");
    }

    /**
     * 인증 상태에 따라 userId 또는 IP 기반으로 토큰을 소비한다.
     *
     * <p>인증된 사용자: userId 기반 → 같은 사용자의 여러 기기에서의 요청을 합산.
     * 비인증 사용자: IP 기반 → NAT 뒤의 다수 사용자가 한도를 공유할 수 있으나,
     * 비인증 상태에서 주문/쿠폰은 인증 필터에서 먼저 차단되므로 실질적 영향 없음.</p>
     */
    private TokenBucket.ConsumeResult consumeToken(HttpServletRequest request, RateLimitPlan plan) {
        Long userId = extractUserId();
        if (userId != null) {
            return rateLimitService.tryConsume(userId, plan);
        }
        String clientIp = clientIpResolver.resolveClientIp(request);
        return rateLimitService.tryConsumeAnonymous(clientIp, plan);
    }

    /**
     * SecurityContext에서 인증된 사용자의 ID를 추출한다.
     * 인증되지 않은 경우 null을 반환한다.
     */
    private Long extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomUserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    /**
     * 성공 응답에 rate limit 상태 헤더를 추가한다.
     *
     * <p>클라이언트는 이 헤더를 통해 남은 요청 수를 확인하고,
     * 한도에 가까워지면 요청 빈도를 조절할 수 있다.</p>
     */
    private void addRateLimitHeaders(HttpServletResponse response, RateLimitPlan plan,
                                      TokenBucket.ConsumeResult result) {
        response.setIntHeader("X-RateLimit-Limit", rateLimitService.getLimit(plan));
        response.setIntHeader("X-RateLimit-Remaining", result.remainingTokens());
    }

    /**
     * 속도 초과 시 429 응답을 반환한다.
     *
     * <p>API 요청(/api/*)은 JSON 응답, SSR 요청은 HTML 에러 메시지를 반환한다.</p>
     */
    private void handleRateLimitExceeded(HttpServletRequest request, HttpServletResponse response,
                                          RateLimitPlan plan, TokenBucket.ConsumeResult result)
            throws IOException {

        String clientIp = clientIpResolver.resolveClientIp(request);
        Long userId = extractUserId();
        log.warn("event=rate_limit_exceeded plan={} uri={} method={} userId={} ip={}",
                plan.name(), request.getRequestURI(), request.getMethod(), userId, clientIp);

        // Jakarta Servlet 6.0에는 SC_TOO_MANY_REQUESTS 상수가 없으므로 직접 지정
        response.setStatus(429);
        response.setIntHeader("X-RateLimit-Limit", rateLimitService.getLimit(plan));
        response.setIntHeader("X-RateLimit-Remaining", 0);
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSec()));

        if (request.getRequestURI().startsWith("/api/")) {
            // REST API: JSON 응답
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"error\":{\"code\":\"RATE_LIMIT_EXCEEDED\","
                    + "\"message\":\"요청이 너무 많습니다. "
                    + result.retryAfterSec() + "초 후에 다시 시도해주세요.\"}}"
            );
        } else {
            // SSR: 간단한 HTML 에러 페이지
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(
                    "<html><body><h2>요청이 너무 많습니다</h2>"
                    + "<p>" + result.retryAfterSec() + "초 후에 다시 시도해주세요.</p>"
                    + "<a href=\"javascript:history.back()\">뒤로 가기</a></body></html>"
            );
        }
    }
}
