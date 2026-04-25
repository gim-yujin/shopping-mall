package com.shop.global.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * HTTP 요청의 URI와 메서드를 기반으로 적용할 {@link RateLimitPlan}을 결정한다.
 *
 * <h3>매핑 규칙</h3>
 * <p>가장 구체적인 경로부터 매칭하여 첫 번째 일치하는 플랜을 반환한다:</p>
 * <ol>
 *   <li>{@code POST /api/v1/orders} → ORDER (가장 엄격)</li>
 *   <li>{@code POST /orders} (SSR 주문 생성) → ORDER</li>
 *   <li>{@code POST /coupons/issue*} → COUPON</li>
 *   <li>{@code GET /api/**} → READ</li>
 *   <li>{@code POST|PUT|DELETE /api/**} → WRITE</li>
 *   <li>기타 → DEFAULT</li>
 * </ol>
 *
 * <h3>SSR 엔드포인트도 제한하는 이유</h3>
 * <p>REST API뿐 아니라 SSR 폼 제출(POST /orders, POST /coupons/issue)도
 * 봇이나 스크립트로 반복 호출될 수 있다. CSRF 토큰이 1차 방어이지만,
 * 세션을 보유한 상태에서의 반복 제출은 CSRF로 막을 수 없다.</p>
 */
public final class RateLimitPlanResolver {

    private RateLimitPlanResolver() {
    }

    /**
     * 요청에 적용할 rate limit 플랜을 결정한다.
     *
     * @param request HTTP 요청
     * @return 적용할 플랜 (null이면 rate limit 미적용)
     */
    public static RateLimitPlan resolve(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String uri = request.getRequestURI();

        // ── 주문 생성 (가장 엄격) ───────────────────
        if ("POST".equals(method)) {
            // REST API 주문 생성
            if (uri.equals("/api/v1/orders")) {
                return RateLimitPlan.ORDER;
            }
            // SSR 폼 주문 생성
            if (uri.equals("/orders")) {
                return RateLimitPlan.ORDER;
            }
        }

        // ── 쿠폰 발급 ─────────────────────────────
        if ("POST".equals(method) && uri.startsWith("/coupons/issue")) {
            return RateLimitPlan.COUPON;
        }

        // ── 플래시 세일 구매 ───────────────────────
        // 경로 형태: POST /api/v1/flash-sales/{id}/items/{itemId}/purchase
        if ("POST".equals(method)
                && uri.startsWith("/api/v1/flash-sales/")
                && uri.endsWith("/purchase")) {
            return RateLimitPlan.FLASH_SALE;
        }

        // ── REST API 읽기/쓰기 분류 ─────────────────
        if (uri.startsWith("/api/")) {
            return isReadMethod(method) ? RateLimitPlan.READ : RateLimitPlan.WRITE;
        }

        // ── SSR GET 요청은 rate limit 미적용 ─────────
        // 상품 목록, 검색 등 SSR 읽기 요청은 Caffeine 캐시가 보호한다.
        // rate limit까지 걸면 정상 브라우징에 영향을 줄 수 있다.
        if (isReadMethod(method)) {
            return null;
        }

        // ── SSR POST 기타 (장바구니 수정 등) ──────────
        return RateLimitPlan.DEFAULT;
    }

    private static boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
