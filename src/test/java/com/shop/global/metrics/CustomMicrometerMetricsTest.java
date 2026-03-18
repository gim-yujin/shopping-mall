package com.shop.global.metrics;

import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.backpressure.PressureLevel;
import com.shop.global.config.AsyncExecutorMetrics;
import com.shop.global.ratelimit.RateLimitPlan;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 13] 커스텀 Micrometer 메트릭 등록 및 값 검증 테스트.
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>AsyncExecutor 메트릭(queue.size, capacity, fill.ratio, active.threads,
 *       rejected.total, completed.total)이 MeterRegistry에 등록됨</li>
 *   <li>Backpressure 메트릭(level, shedding.active)이 등록되고
 *       초기 상태(NORMAL)에서 올바른 값을 반환</li>
 *   <li>OrderMetrics 타이머와 카운터가 등록되고 기록이 동작</li>
 *   <li>RateLimitMetrics 카운터가 모든 플랜에 대해 등록됨</li>
 * </ol>
 *
 * <h3>왜 MeterRegistry 검증이 중요한가?</h3>
 * <p>MeterBinder의 bindTo()가 호출되지 않으면 메트릭이 Prometheus에 노출되지 않는다.
 * Spring Boot는 MeterBinder 빈을 자동 감지하여 bindTo()를 호출하지만,
 * 빈 등록 실패(의존성 누락, 조건부 등록 등)로 메트릭이 누락될 수 있다.
 * 이 테스트는 모든 커스텀 메트릭이 실제로 등록되었음을 보장한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CustomMicrometerMetricsTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AsyncExecutorMetrics asyncExecutorMetrics;

    @Autowired
    private BackpressureDetector backpressureDetector;

    @Autowired
    private OrderMetrics orderMetrics;

    @Autowired
    private RateLimitMetrics rateLimitMetrics;

    // =========================================================================
    // 시나리오 1: AsyncExecutor 메트릭 등록 검증
    // =========================================================================

    /**
     * [Phase 13] AsyncExecutor의 6개 메트릭이 MeterRegistry에 등록되어
     * Prometheus /actuator/prometheus 엔드포인트에서 스크래핑 가능한지 검증.
     */
    @Test
    @DisplayName("AsyncExecutor 메트릭이 MeterRegistry에 등록되어 값 조회 가능")
    void asyncExecutorMetrics_registeredInMeterRegistry() {
        // 큐 상태 게이지
        Gauge queueSize = meterRegistry.find("shop.async.queue.size").gauge();
        assertThat(queueSize)
                .as("shop.async.queue.size 게이지가 등록되어야 합니다")
                .isNotNull();
        assertThat(queueSize.value())
                .as("큐 크기는 0 이상이어야 합니다")
                .isGreaterThanOrEqualTo(0);

        Gauge queueCapacity = meterRegistry.find("shop.async.queue.capacity").gauge();
        assertThat(queueCapacity)
                .as("shop.async.queue.capacity 게이지가 등록되어야 합니다")
                .isNotNull();
        assertThat(queueCapacity.value())
                .as("큐 용량은 양수여야 합니다 (AsyncConfig에서 500으로 설정)")
                .isGreaterThan(0);

        Gauge fillRatio = meterRegistry.find("shop.async.queue.fill.ratio").gauge();
        assertThat(fillRatio)
                .as("shop.async.queue.fill.ratio 게이지가 등록되어야 합니다")
                .isNotNull();
        assertThat(fillRatio.value())
                .as("초기 큐 사용률은 0에 가까워야 합니다")
                .isBetween(0.0, 1.0);

        // 스레드 상태
        Gauge activeThreads = meterRegistry.find("shop.async.active.threads").gauge();
        assertThat(activeThreads)
                .as("shop.async.active.threads 게이지가 등록되어야 합니다")
                .isNotNull();

        // 누적 카운터
        Gauge rejectedTotal = meterRegistry.find("shop.async.rejected.total").gauge();
        assertThat(rejectedTotal)
                .as("shop.async.rejected.total 게이지가 등록되어야 합니다")
                .isNotNull();

        Gauge completedTotal = meterRegistry.find("shop.async.completed.total").gauge();
        assertThat(completedTotal)
                .as("shop.async.completed.total 게이지가 등록되어야 합니다")
                .isNotNull();
    }

    // =========================================================================
    // 시나리오 2: Backpressure 메트릭 등록 및 초기값 검증
    // =========================================================================

    /**
     * [Phase 13] Backpressure 메트릭이 등록되고 초기 상태(NORMAL)에서
     * 올바른 값을 반환하는지 검증.
     */
    @Test
    @DisplayName("Backpressure 메트릭이 NORMAL 상태에서 올바른 값 반환")
    void backpressureMetrics_normalStateValues() {
        Gauge pressureLevel = meterRegistry.find("shop.backpressure.level").gauge();
        assertThat(pressureLevel)
                .as("shop.backpressure.level 게이지가 등록되어야 합니다")
                .isNotNull();
        assertThat((int) pressureLevel.value())
                .as("초기 상태에서 pressure level은 NORMAL(0)이어야 합니다")
                .isEqualTo(PressureLevel.NORMAL.ordinal());

        Gauge sheddingActive = meterRegistry.find("shop.backpressure.shedding.active").gauge();
        assertThat(sheddingActive)
                .as("shop.backpressure.shedding.active 게이지가 등록되어야 합니다")
                .isNotNull();
        assertThat(sheddingActive.value())
                .as("초기 상태에서 shedding은 비활성(0.0)이어야 합니다")
                .isEqualTo(0.0);
    }

    // =========================================================================
    // 시나리오 3: OrderMetrics 타이머/카운터 동작 검증
    // =========================================================================

    /**
     * [Phase 13] OrderMetrics의 Timer.Sample → recordSuccess/recordFailure 흐름이
     * MeterRegistry에 올바르게 기록되는지 검증.
     */
    @Test
    @DisplayName("OrderMetrics 타이머와 카운터가 기록되고 조회 가능")
    void orderMetrics_timerAndCounterRecording() {
        // 타이머 등록 확인
        Timer creationTimer = meterRegistry.find("shop.order.creation.duration").timer();
        assertThat(creationTimer)
                .as("shop.order.creation.duration 타이머가 등록되어야 합니다")
                .isNotNull();

        long countBefore = creationTimer.count();

        // 성공 기록
        Timer.Sample sample = orderMetrics.startTimer();
        orderMetrics.recordSuccess(sample);

        assertThat(creationTimer.count())
                .as("recordSuccess 후 타이머 카운트가 1 증가해야 합니다")
                .isEqualTo(countBefore + 1);
        assertThat(creationTimer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .as("기록된 시간이 0보다 커야 합니다")
                .isGreaterThan(0);

        // 성공 카운터 확인
        double successCount = meterRegistry.find("shop.order.creation.total")
                .tag("result", "success")
                .counter()
                .count();
        assertThat(successCount)
                .as("성공 카운터가 1 이상이어야 합니다")
                .isGreaterThanOrEqualTo(1.0);
    }

    // =========================================================================
    // 시나리오 4: RateLimitMetrics 플랜별 카운터 등록 검증
    // =========================================================================

    /**
     * [Phase 13] 모든 RateLimitPlan에 대해 allowed/rejected 카운터가
     * 등록되고 증분이 정확히 기록되는지 검증.
     */
    @Test
    @DisplayName("RateLimitMetrics가 모든 플랜에 대해 카운터 등록 및 기록")
    void rateLimitMetrics_allPlansRegistered() {
        for (RateLimitPlan plan : RateLimitPlan.values()) {
            // allowed 카운터 존재 확인
            assertThat(meterRegistry.find("shop.ratelimit.requests.total")
                    .tag("plan", plan.name())
                    .tag("result", "allowed")
                    .counter())
                    .as("플랜 %s의 allowed 카운터가 등록되어야 합니다", plan.name())
                    .isNotNull();

            // rejected 카운터 존재 확인
            assertThat(meterRegistry.find("shop.ratelimit.requests.total")
                    .tag("plan", plan.name())
                    .tag("result", "rejected")
                    .counter())
                    .as("플랜 %s의 rejected 카운터가 등록되어야 합니다", plan.name())
                    .isNotNull();
        }

        // 증분 기록 검증 — ORDER 플랜으로 테스트
        double beforeAllowed = meterRegistry.find("shop.ratelimit.requests.total")
                .tag("plan", "ORDER").tag("result", "allowed")
                .counter().count();

        rateLimitMetrics.recordAllowed(RateLimitPlan.ORDER);
        rateLimitMetrics.recordRejected(RateLimitPlan.ORDER);

        double afterAllowed = meterRegistry.find("shop.ratelimit.requests.total")
                .tag("plan", "ORDER").tag("result", "allowed")
                .counter().count();
        double afterRejected = meterRegistry.find("shop.ratelimit.requests.total")
                .tag("plan", "ORDER").tag("result", "rejected")
                .counter().count();

        assertThat(afterAllowed)
                .as("recordAllowed 호출 후 allowed 카운터가 증가해야 합니다")
                .isGreaterThan(beforeAllowed);
        assertThat(afterRejected)
                .as("recordRejected 호출 후 rejected 카운터가 0보다 커야 합니다")
                .isGreaterThan(0);
    }
}
