package com.shop.global.metrics;

import com.shop.global.backpressure.BackpressureDetector;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * [Phase 13] Backpressure 상태 메트릭을 Micrometer에 등록하여 Prometheus로 노출한다.
 *
 * <h3>왜 Backpressure 메트릭이 필요한가?</h3>
 * <p>Phase 12에서 구현한 Graceful Degradation은 CRITICAL 상태에서 비필수 작업을 폐기한다.
 * 그런데 이 동작이 실제로 얼마나 자주 발생하는지, 얼마나 오래 지속되는지를
 * 로그만으로는 정량적으로 파악하기 어렵다.
 * Prometheus 게이지로 노출하면 Grafana에서 시계열 차트로 패턴을 확인하고,
 * Alertmanager 규칙으로 CRITICAL 상태가 N분 이상 지속되면 알림을 받을 수 있다.</p>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.backpressure.level</td><td>Gauge</td>
 *       <td>현재 부하 수준 (0=NORMAL, 1=ELEVATED, 2=CRITICAL)</td></tr>
 *   <tr><td>shop.backpressure.shedding.active</td><td>Gauge</td>
 *       <td>비필수 작업 폐기 활성 여부 (0=정상, 1=폐기 중)</td></tr>
 * </table>
 *
 * <p><b>Grafana 활용:</b> {@code shop.backpressure.level} 값이 2(CRITICAL)인 시간대를
 * HTTP 에러율이나 응답 시간 차트와 겹쳐 보면, 부하 제어가 실제로 시스템을
 * 보호하고 있는지 상관관계를 확인할 수 있다.</p>
 */
@Component
public class BackpressureMeterBinder implements MeterBinder {

    private final BackpressureDetector backpressureDetector;

    public BackpressureMeterBinder(BackpressureDetector backpressureDetector) {
        this.backpressureDetector = backpressureDetector;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // 부하 수준: 0=NORMAL, 1=ELEVATED, 2=CRITICAL
        // Prometheus에서 shop_backpressure_level >= 2 조건으로 알림 설정 가능
        Gauge.builder("shop.backpressure.level", backpressureDetector,
                        d -> d.getPressureLevel().ordinal())
                .description("시스템 부하 수준 (0=NORMAL, 1=ELEVATED, 2=CRITICAL)")
                .register(registry);

        // 비필수 작업 폐기 활성 여부 (0 또는 1)
        // Grafana에서 annotation으로 활용하면 폐기 구간을 시각적으로 표시할 수 있다
        Gauge.builder("shop.backpressure.shedding.active", backpressureDetector,
                        d -> d.shouldShedNonCritical() ? 1.0 : 0.0)
                .description("비필수 작업 폐기 활성 여부 (0=정상, 1=폐기 중)")
                .register(registry);
    }
}
