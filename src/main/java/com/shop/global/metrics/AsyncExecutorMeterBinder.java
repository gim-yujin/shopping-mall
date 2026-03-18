package com.shop.global.metrics;

import com.shop.global.config.AsyncExecutorMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * [Phase 13] 비동기 Executor 메트릭을 Micrometer에 등록하여 Prometheus로 노출한다.
 *
 * <h3>왜 MeterBinder를 사용하는가?</h3>
 * <p>{@link AsyncExecutorMetrics}는 로그 출력용으로 설계되어 Prometheus에는 노출되지 않았다.
 * Phase 12에서 asyncExecutor 큐 포화도가 Graceful Degradation의 핵심 지표임을 확인했으므로,
 * 이를 Prometheus/Grafana에서 실시간 모니터링할 수 있도록 Micrometer 게이지/카운터로 등록한다.</p>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.async.queue.size</td><td>Gauge</td><td>현재 큐에 대기 중인 작업 수</td></tr>
 *   <tr><td>shop.async.queue.capacity</td><td>Gauge</td><td>큐 전체 용량</td></tr>
 *   <tr><td>shop.async.queue.fill.ratio</td><td>Gauge</td><td>큐 사용률 (0.0~1.0)</td></tr>
 *   <tr><td>shop.async.active.threads</td><td>Gauge</td><td>작업 실행 중인 스레드 수</td></tr>
 *   <tr><td>shop.async.rejected.total</td><td>Gauge</td><td>누적 거부 작업 수 (단조 증가)</td></tr>
 *   <tr><td>shop.async.completed.total</td><td>Gauge</td><td>누적 완료 작업 수 (단조 증가)</td></tr>
 * </table>
 *
 * <p><b>참고:</b> rejected.total과 completed.total은 의미상 카운터이지만,
 * {@link AsyncExecutorMetrics}의 값이 JVM 내 AtomicLong이라 재시작 시 0으로 초기화된다.
 * Prometheus Counter는 단조 증가를 보장해야 하므로, Gauge로 등록하고
 * Prometheus의 {@code increase()} 함수로 증분을 계산한다.</p>
 */
@Component
public class AsyncExecutorMeterBinder implements MeterBinder {

    private final AsyncExecutorMetrics asyncExecutorMetrics;

    public AsyncExecutorMeterBinder(AsyncExecutorMetrics asyncExecutorMetrics) {
        this.asyncExecutorMetrics = asyncExecutorMetrics;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // ── 큐 상태 게이지 ──
        Gauge.builder("shop.async.queue.size", asyncExecutorMetrics, AsyncExecutorMetrics::getQueueSize)
                .description("비동기 Executor 큐에 대기 중인 작업 수")
                .register(registry);

        Gauge.builder("shop.async.queue.capacity", asyncExecutorMetrics, AsyncExecutorMetrics::getQueueCapacity)
                .description("비동기 Executor 큐 전체 용량")
                .register(registry);

        Gauge.builder("shop.async.queue.fill.ratio", asyncExecutorMetrics,
                        m -> m.getQueueCapacity() > 0
                                ? (double) m.getQueueSize() / m.getQueueCapacity()
                                : 0.0)
                .description("비동기 Executor 큐 사용률 (0.0~1.0)")
                .register(registry);

        // ── 스레드 활성 상태 ──
        Gauge.builder("shop.async.active.threads", asyncExecutorMetrics, AsyncExecutorMetrics::getActiveCount)
                .description("비동기 Executor에서 작업 실행 중인 스레드 수")
                .register(registry);

        // ── 누적 카운터 (Gauge로 등록, Prometheus increase()로 증분 계산) ──
        Gauge.builder("shop.async.rejected.total", asyncExecutorMetrics, AsyncExecutorMetrics::getRejectedTotal)
                .description("비동기 Executor에서 거부된 작업 누적 수")
                .register(registry);

        Gauge.builder("shop.async.completed.total", asyncExecutorMetrics, AsyncExecutorMetrics::getCompletedTotal)
                .description("비동기 Executor에서 완료된 작업 누적 수")
                .register(registry);
    }
}
