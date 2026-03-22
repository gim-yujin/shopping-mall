package com.shop.global.metrics;

import com.shop.global.ratelimit.RateLimitPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitMetrics 분기 커버리지 보강 테스트.
 *
 * <p>기존 MetricsBranchCoverageTest에서 다루지 않은 RateLimitMetrics를 검증한다:
 * - recordAllowed: 모든 플랜별 허용 카운터 증가
 * - recordRejected: 모든 플랜별 거부 카운터 증가</p>
 */
class RateLimitMetricsBranchTest {

    @Test
    @DisplayName("recordAllowed — 모든 플랜의 허용 카운터가 증가한다")
    void recordAllowed_incrementsAllPlanCounters() {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetrics metrics = new RateLimitMetrics(registry);

        // when: 모든 플랜에 대해 recordAllowed 호출
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            metrics.recordAllowed(plan);
        }

        // then: 각 플랜의 allowed 카운터가 1.0
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            assertThat(registry.counter("shop.ratelimit.requests.total",
                    "plan", plan.name(), "result", "allowed").count())
                    .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("recordRejected — 모든 플랜의 거부 카운터가 증가한다")
    void recordRejected_incrementsAllPlanCounters() {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMetrics metrics = new RateLimitMetrics(registry);

        // when: 모든 플랜에 대해 recordRejected 호출
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            metrics.recordRejected(plan);
            metrics.recordRejected(plan); // 2회 호출
        }

        // then: 각 플랜의 rejected 카운터가 2.0
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            assertThat(registry.counter("shop.ratelimit.requests.total",
                    "plan", plan.name(), "result", "rejected").count())
                    .isEqualTo(2.0);
        }
    }
}
