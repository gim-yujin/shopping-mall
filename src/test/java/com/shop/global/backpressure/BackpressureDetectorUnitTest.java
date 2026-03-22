package com.shop.global.backpressure;

import com.shop.global.config.AsyncExecutorMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BackpressureDetector + BackpressureHealthIndicator 단위 테스트.
 *
 * <p>기존 BackpressureGracefulDegradationTest는 통합 테스트(SpringBootTest)로
 * 실제 Executor를 사용하여 큐를 채우는 방식이었다. 하지만 다음 분기가 커버되지 않았다:
 * - capacity <= 0 (Executor 미초기화): getPressureLevel() → NORMAL, getQueueFillRatio() → 0.0
 * - 정확한 임계값 경계: 60% ELEVATED, 80% CRITICAL
 * - HealthIndicator CRITICAL → Health.down() 분기
 *
 * <p>Mock AsyncExecutorMetrics로 큐 상태를 직접 제어하여 모든 분기를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class BackpressureDetectorUnitTest {

    @Mock
    private AsyncExecutorMetrics metrics;

    // ── getPressureLevel ──

    @Test
    @DisplayName("큐 용량이 0이면 NORMAL 반환 (Executor 미초기화)")
    void getPressureLevel_zeroCapacity_returnsNormal() {
        // given: capacity <= 0 분기 — Executor가 아직 바인딩되지 않은 테스트 환경
        when(metrics.getQueueCapacity()).thenReturn(0);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then
        assertThat(detector.getPressureLevel()).isEqualTo(PressureLevel.NORMAL);
    }

    @Test
    @DisplayName("큐 사용률 < 60% → NORMAL")
    void getPressureLevel_below60percent_returnsNormal() {
        // given: 큐 사용률 50% (100/200)
        when(metrics.getQueueCapacity()).thenReturn(200);
        when(metrics.getQueueSize()).thenReturn(100);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then
        assertThat(detector.getPressureLevel()).isEqualTo(PressureLevel.NORMAL);
        assertThat(detector.shouldShedNonCritical()).isFalse();
    }

    @Test
    @DisplayName("큐 사용률 >= 60% & < 80% → ELEVATED")
    void getPressureLevel_at60percent_returnsElevated() {
        // given: 큐 사용률 정확히 60% (120/200) — ELEVATED 임계값 경계
        when(metrics.getQueueCapacity()).thenReturn(200);
        when(metrics.getQueueSize()).thenReturn(120);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then: ELEVATED이지만 아직 비필수 작업 폐기하지 않음
        assertThat(detector.getPressureLevel()).isEqualTo(PressureLevel.ELEVATED);
        assertThat(detector.shouldShedNonCritical()).isFalse();
    }

    @Test
    @DisplayName("큐 사용률 >= 80% → CRITICAL + 비필수 작업 폐기")
    void getPressureLevel_at80percent_returnsCritical() {
        // given: 큐 사용률 정확히 80% (160/200) — CRITICAL 임계값
        when(metrics.getQueueCapacity()).thenReturn(200);
        when(metrics.getQueueSize()).thenReturn(160);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then: CRITICAL → shouldShedNonCritical() = true
        assertThat(detector.getPressureLevel()).isEqualTo(PressureLevel.CRITICAL);
        assertThat(detector.shouldShedNonCritical()).isTrue();
    }

    // ── getQueueFillRatio ──

    @Test
    @DisplayName("큐 용량이 0이면 fill ratio = 0.0")
    void getQueueFillRatio_zeroCapacity_returnsZero() {
        // given: capacity <= 0 분기
        when(metrics.getQueueCapacity()).thenReturn(0);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then
        assertThat(detector.getQueueFillRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("큐 사용률 계산이 정확하다")
    void getQueueFillRatio_calculatesCorrectly() {
        // given: 큐 크기 75, 용량 100 → 75%
        when(metrics.getQueueCapacity()).thenReturn(100);
        when(metrics.getQueueSize()).thenReturn(75);
        BackpressureDetector detector = new BackpressureDetector(metrics);

        // when & then
        assertThat(detector.getQueueFillRatio()).isEqualTo(0.75);
    }

    // ── BackpressureHealthIndicator ──

    @Test
    @DisplayName("CRITICAL 상태 → Health DOWN (503 반환)")
    void healthIndicator_critical_returnsDown() {
        // given: CRITICAL 상태 — 큐 90% 포화
        when(metrics.getQueueCapacity()).thenReturn(100);
        when(metrics.getQueueSize()).thenReturn(90);
        when(metrics.getRejectedTotal()).thenReturn(5L);
        when(metrics.getCompletedTotal()).thenReturn(1000L);
        BackpressureDetector detector = new BackpressureDetector(metrics);
        BackpressureHealthIndicator indicator = new BackpressureHealthIndicator(detector, metrics);

        // when
        Health health = indicator.health();

        // then: CRITICAL → Health.down() → 로드밸런서가 503 반환
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("pressureLevel")).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("ELEVATED 상태 → Health UP (200 반환)")
    void healthIndicator_elevated_returnsUp() {
        // given: ELEVATED 상태 — 큐 70%
        when(metrics.getQueueCapacity()).thenReturn(100);
        when(metrics.getQueueSize()).thenReturn(70);
        when(metrics.getRejectedTotal()).thenReturn(0L);
        when(metrics.getCompletedTotal()).thenReturn(500L);
        BackpressureDetector detector = new BackpressureDetector(metrics);
        BackpressureHealthIndicator indicator = new BackpressureHealthIndicator(detector, metrics);

        // when
        Health health = indicator.health();

        // then: ELEVATED는 아직 UP — 경고 수준일 뿐 서비스 불가는 아님
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("pressureLevel")).isEqualTo("ELEVATED");
    }
}
