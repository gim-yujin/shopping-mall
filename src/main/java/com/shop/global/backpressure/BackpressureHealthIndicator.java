package com.shop.global.backpressure;

import com.shop.global.config.AsyncExecutorMetrics;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * [Phase 12] 비동기 Executor 부하 수준을 Spring Actuator Health 엔드포인트에 노출한다.
 *
 * <h3>왜 Health 엔드포인트인가?</h3>
 * <p>Prometheus 메트릭은 시계열 모니터링에 적합하지만, 로드밸런서의 헬스체크는
 * {@code /actuator/health} 응답의 HTTP 상태 코드(200 vs 503)를 기준으로 동작한다.
 * CRITICAL 상태에서 503을 반환하면 로드밸런서가 해당 인스턴스로의 트래픽을 줄여
 * 과부하 인스턴스를 자동으로 보호할 수 있다.</p>
 *
 * <h3>상태 매핑</h3>
 * <ul>
 *   <li>NORMAL: UP (200)</li>
 *   <li>ELEVATED: UP (200) + 경고 상세 정보</li>
 *   <li>CRITICAL: DOWN (503) — 로드밸런서가 트래픽 분산 조정</li>
 * </ul>
 */
@Component
public class BackpressureHealthIndicator implements HealthIndicator {

    private final BackpressureDetector backpressureDetector;
    private final AsyncExecutorMetrics metrics;

    public BackpressureHealthIndicator(BackpressureDetector backpressureDetector,
                                        AsyncExecutorMetrics metrics) {
        this.backpressureDetector = backpressureDetector;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        PressureLevel level = backpressureDetector.getPressureLevel();
        double fillRatio = backpressureDetector.getQueueFillRatio();

        Health.Builder builder = (level == PressureLevel.CRITICAL)
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("pressureLevel", level.name())
                .withDetail("queueFillRatio", String.format("%.1f%%", fillRatio * 100))
                .withDetail("queueSize", metrics.getQueueSize())
                .withDetail("queueCapacity", metrics.getQueueCapacity())
                .withDetail("rejectedTotal", metrics.getRejectedTotal())
                .withDetail("completedTotal", metrics.getCompletedTotal())
                .build();
    }
}
