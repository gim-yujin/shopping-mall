package com.shop.global.metrics;

import com.shop.domain.search.service.SearchLogBatchAccumulator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * [Phase 19] 검색 로그 배치 누적기 메트릭을 Micrometer에 등록하여 Prometheus로 노출한다.
 *
 * <h3>모니터링 목적</h3>
 * <p>배치 누적기의 버퍼 상태와 처리 통계를 실시간으로 추적하여
 * 버퍼 오버플로우, 저장 실패, 플러시 지연을 조기에 감지한다.</p>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.search.log.buffer.size</td><td>Gauge</td><td>현재 버퍼에 대기 중인 로그 수</td></tr>
 *   <tr><td>shop.search.log.buffer.max</td><td>Gauge</td><td>버퍼 최대 크기</td></tr>
 *   <tr><td>shop.search.log.buffer.fill.ratio</td><td>Gauge</td><td>버퍼 사용률 (0.0~1.0)</td></tr>
 *   <tr><td>shop.search.log.added.total</td><td>Gauge</td><td>누적 추가 건수</td></tr>
 *   <tr><td>shop.search.log.flushed.total</td><td>Gauge</td><td>누적 DB 저장 건수</td></tr>
 *   <tr><td>shop.search.log.dropped.total</td><td>Gauge</td><td>누적 폐기 건수 (오버플로우 + 저장 실패)</td></tr>
 *   <tr><td>shop.search.log.flush.count</td><td>Gauge</td><td>누적 플러시 횟수</td></tr>
 * </table>
 *
 * <p>알림 기준 예시:</p>
 * <ul>
 *   <li>{@code shop.search.log.buffer.fill.ratio > 0.8}: 버퍼 포화 임박 — batch-size 또는 flush-interval 조정 필요</li>
 *   <li>{@code increase(shop.search.log.dropped.total[5m]) > 0}: 로그 유실 발생 — 원인 조사 필요</li>
 * </ul>
 */
@Component
public class SearchLogBatchMeterBinder implements MeterBinder {

    private final SearchLogBatchAccumulator accumulator;

    public SearchLogBatchMeterBinder(SearchLogBatchAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // ── 버퍼 상태 게이지 ──
        Gauge.builder("shop.search.log.buffer.size", accumulator, SearchLogBatchAccumulator::getBufferSize)
                .description("검색 로그 배치 버퍼에 대기 중인 로그 수")
                .register(registry);

        Gauge.builder("shop.search.log.buffer.max", accumulator, SearchLogBatchAccumulator::getMaxBufferSize)
                .description("검색 로그 배치 버퍼 최대 크기")
                .register(registry);

        Gauge.builder("shop.search.log.buffer.fill.ratio", accumulator,
                        a -> a.getMaxBufferSize() > 0
                                ? (double) a.getBufferSize() / a.getMaxBufferSize()
                                : 0.0)
                .description("검색 로그 배치 버퍼 사용률 (0.0~1.0)")
                .register(registry);

        // ── 누적 카운터 (Gauge로 등록 — JVM 재시작 시 0 초기화) ──
        Gauge.builder("shop.search.log.added.total", accumulator,
                        a -> (double) a.getTotalAdded())
                .description("검색 로그 누적 추가 건수")
                .register(registry);

        Gauge.builder("shop.search.log.flushed.total", accumulator,
                        a -> (double) a.getTotalFlushed())
                .description("검색 로그 누적 DB 저장 건수")
                .register(registry);

        Gauge.builder("shop.search.log.dropped.total", accumulator,
                        a -> (double) a.getTotalDropped())
                .description("검색 로그 누적 폐기 건수 (오버플로우 + 저장 실패)")
                .register(registry);

        Gauge.builder("shop.search.log.flush.count", accumulator,
                        a -> (double) a.getFlushCount())
                .description("검색 로그 배치 플러시 횟수")
                .register(registry);
    }
}
