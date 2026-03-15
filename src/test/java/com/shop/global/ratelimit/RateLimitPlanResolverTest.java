package com.shop.global.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitPlanResolver 단위 테스트.
 *
 * <p>요청 URI와 HTTP 메서드에 따라 올바른 {@link RateLimitPlan}이 반환되는지 검증한다.</p>
 */
class RateLimitPlanResolverTest {

    @Test
    @DisplayName("POST /api/v1/orders → ORDER 플랜")
    void apiOrderCreation() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.ORDER);
    }

    @Test
    @DisplayName("POST /orders (SSR) → ORDER 플랜")
    void ssrOrderCreation() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.ORDER);
    }

    @Test
    @DisplayName("POST /coupons/issue → COUPON 플랜")
    void couponIssuance() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/coupons/issue");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.COUPON);
    }

    @Test
    @DisplayName("POST /coupons/issue/123 → COUPON 플랜")
    void couponIssuanceById() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/coupons/issue/123");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.COUPON);
    }

    @Test
    @DisplayName("GET /api/v1/products → READ 플랜")
    void apiRead() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.READ);
    }

    @Test
    @DisplayName("POST /api/v1/orders/100/cancel → WRITE 플랜")
    void apiWrite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders/100/cancel");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.WRITE);
    }

    @Test
    @DisplayName("DELETE /api/v1/reviews/1 → WRITE 플랜")
    void apiDelete() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/reviews/1");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.WRITE);
    }

    @Test
    @DisplayName("GET /products/1 (SSR 읽기) → null (rate limit 미적용)")
    void ssrReadNoRateLimit() {
        // SSR 읽기 요청은 Caffeine 캐시가 보호하므로 rate limit 미적용
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products/1");

        assertThat(RateLimitPlanResolver.resolve(request)).isNull();
    }

    @Test
    @DisplayName("POST /cart/add (SSR 기타 쓰기) → DEFAULT 플랜")
    void ssrDefaultWrite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cart/add");

        assertThat(RateLimitPlanResolver.resolve(request)).isEqualTo(RateLimitPlan.DEFAULT);
    }
}
