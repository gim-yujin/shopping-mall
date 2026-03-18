package com.shop.global.metrics;

import com.shop.global.ratelimit.RateLimitPlan;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * [Phase 13] Rate Limit 도메인 커스텀 메트릭.
 *
 * <h3>왜 Rate Limit 메트릭이 필요한가?</h3>
 * <p>Rate Limit 필터는 429 응답을 반환하지만, {@code http_server_requests_seconds}는
 * 상태 코드(429)만 기록할 뿐 어떤 플랜(ORDER, COUPON, READ 등)에서 제한되었는지 알 수 없다.
 * 플랜별 거부 횟수를 추적하면 다음을 파악할 수 있다:</p>
 * <ul>
 *   <li>ORDER 플랜 거부 급증 → 봇에 의한 주문 공격 의심</li>
 *   <li>READ 플랜 거부 급증 → 크롤러 활동 증가</li>
 *   <li>COUPON 플랜 거부 급증 → 선착순 이벤트 시 사용자 재시도 폭증</li>
 * </ul>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>태그</th><th>설명</th></tr>
 *   <tr><td>shop.ratelimit.requests.total</td><td>plan, result=allowed</td>
 *       <td>플랜별 허용된 요청 수</td></tr>
 *   <tr><td>shop.ratelimit.requests.total</td><td>plan, result=rejected</td>
 *       <td>플랜별 거부(429)된 요청 수</td></tr>
 * </table>
 */
@Component
public class RateLimitMetrics {

    private final Map<RateLimitPlan, Counter> allowedCounters;
    private final Map<RateLimitPlan, Counter> rejectedCounters;

    public RateLimitMetrics(MeterRegistry registry) {
        // 모든 플랜에 대해 미리 카운터를 등록하여, 요청 경로에서 Map.get()만 수행한다.
        // 카운터 초기화를 지연하면 첫 요청 시 동기화 비용이 발생할 수 있다.
        this.allowedCounters = new EnumMap<>(RateLimitPlan.class);
        this.rejectedCounters = new EnumMap<>(RateLimitPlan.class);

        for (RateLimitPlan plan : RateLimitPlan.values()) {
            allowedCounters.put(plan, Counter.builder("shop.ratelimit.requests.total")
                    .description("Rate Limit 플랜별 요청 수")
                    .tag("plan", plan.name())
                    .tag("result", "allowed")
                    .register(registry));

            rejectedCounters.put(plan, Counter.builder("shop.ratelimit.requests.total")
                    .description("Rate Limit 플랜별 요청 수")
                    .tag("plan", plan.name())
                    .tag("result", "rejected")
                    .register(registry));
        }
    }

    /**
     * 허용된 요청을 기록한다. RateLimitFilter에서 토큰 소비 성공 시 호출.
     */
    public void recordAllowed(RateLimitPlan plan) {
        allowedCounters.get(plan).increment();
    }

    /**
     * 거부된 요청을 기록한다. RateLimitFilter에서 토큰 소진(429) 시 호출.
     */
    public void recordRejected(RateLimitPlan plan) {
        rejectedCounters.get(plan).increment();
    }
}
