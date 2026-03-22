package com.shop.global.metrics;

import com.shop.domain.search.service.SearchLogBatchAccumulator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 메트릭 클래스 분기 커버리지 보강 테스트.
 *
 * <p>기존 CustomMicrometerMetricsTest(통합 테스트)에서는 빈 등록만 검증했으므로
 * 개별 메트릭 클래스의 record/increment 메서드가 커버되지 않았다.
 * 이 테스트에서는 다음을 검증한다:
 * - IdempotencyMetrics: 6개 Counter의 increment 메서드
 * - OutboxMetrics: 3개 Counter의 increment 메서드
 * - SearchLogBatchMeterBinder: 7개 Gauge 등록 + 값 조회</p>
 */
@ExtendWith(MockitoExtension.class)
class MetricsBranchCoverageTest {

    // ── IdempotencyMetrics ──

    @Nested
    @DisplayName("IdempotencyMetrics — 멱등성 카운터 증가")
    class IdempotencyMetricsTests {

        @Test
        @DisplayName("모든 카운터가 등록되고 증가된다")
        void allCounters_registeredAndIncremented() {
            // given: SimpleMeterRegistry로 카운터 등록
            MeterRegistry registry = new SimpleMeterRegistry();
            IdempotencyMetrics metrics = new IdempotencyMetrics(registry);

            // when: 모든 기록 메서드 호출
            metrics.recordNew();
            metrics.recordDuplicateCompleted();
            metrics.recordDuplicateProcessing();
            metrics.recordConflict();
            metrics.recordRetry();
            metrics.recordStaleRecovered(3);

            // then: 각 카운터가 정확히 증가
            assertThat(registry.counter("shop.idempotency.requests.total",
                    "result", "new").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.idempotency.requests.total",
                    "result", "duplicate_completed").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.idempotency.requests.total",
                    "result", "duplicate_processing").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.idempotency.requests.total",
                    "result", "conflict").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.idempotency.requests.total",
                    "result", "retry").count()).isEqualTo(1.0);
            // staleRecovered: 3 증가
            assertThat(registry.counter("shop.idempotency.stale.recovered.total").count())
                    .isEqualTo(3.0);
        }
    }

    // ── OutboxMetrics ──

    @Nested
    @DisplayName("OutboxMetrics — Outbox 카운터 증가")
    class OutboxMetricsTests {

        @Mock
        private com.shop.global.outbox.OutboxEventRepository repository;

        @Test
        @DisplayName("모든 카운터가 등록되고 증가된다")
        void allCounters_registeredAndIncremented() {
            // given: SimpleMeterRegistry로 카운터 + 게이지 등록
            MeterRegistry registry = new SimpleMeterRegistry();
            OutboxMetrics metrics = new OutboxMetrics(registry, repository);

            // when: 모든 기록 메서드 호출
            metrics.recordProcessed();
            metrics.recordDeadLetter();
            metrics.recordRetry();

            // then: 각 카운터가 정확히 증가
            assertThat(registry.counter("shop.outbox.processed.total").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.outbox.dead.letter.total").count()).isEqualTo(1.0);
            assertThat(registry.counter("shop.outbox.retry.total").count()).isEqualTo(1.0);
        }
    }

    // ── SearchLogBatchMeterBinder ──

    @Nested
    @DisplayName("SearchLogBatchMeterBinder — 검색 로그 배치 게이지 등록")
    class SearchLogBatchMeterBinderTests {

        @Mock
        private SearchLogBatchAccumulator accumulator;

        @Test
        @DisplayName("bindTo — 7개 게이지가 등록되고 값을 조회할 수 있다")
        void bindTo_registersAllGauges() {
            // given: accumulator 상태 설정
            when(accumulator.getBufferSize()).thenReturn(50);
            when(accumulator.getMaxBufferSize()).thenReturn(1000);
            when(accumulator.getTotalAdded()).thenReturn(5000L);
            when(accumulator.getTotalFlushed()).thenReturn(4900L);
            when(accumulator.getTotalDropped()).thenReturn(10L);
            when(accumulator.getFlushCount()).thenReturn(100L);

            MeterRegistry registry = new SimpleMeterRegistry();
            SearchLogBatchMeterBinder binder = new SearchLogBatchMeterBinder(accumulator);

            // when: Micrometer에 바인딩
            binder.bindTo(registry);

            // then: 모든 게이지가 등록되고 올바른 값 반환
            assertThat(registry.get("shop.search.log.buffer.size").gauge().value())
                    .isEqualTo(50.0);
            assertThat(registry.get("shop.search.log.buffer.max").gauge().value())
                    .isEqualTo(1000.0);
            // fill ratio: 50 / 1000 = 0.05
            assertThat(registry.get("shop.search.log.buffer.fill.ratio").gauge().value())
                    .isEqualTo(0.05);
            assertThat(registry.get("shop.search.log.added.total").gauge().value())
                    .isEqualTo(5000.0);
            assertThat(registry.get("shop.search.log.flushed.total").gauge().value())
                    .isEqualTo(4900.0);
            assertThat(registry.get("shop.search.log.dropped.total").gauge().value())
                    .isEqualTo(10.0);
            assertThat(registry.get("shop.search.log.flush.count").gauge().value())
                    .isEqualTo(100.0);
        }

        @Test
        @DisplayName("maxBufferSize가 0이면 fill ratio = 0.0")
        void bindTo_zeroMaxBufferSize_fillRatioZero() {
            // given: maxBufferSize = 0인 엣지 케이스 — 0 나누기 방지 분기
            // Gauge 람다는 registry에서 값 조회 시점에 호출되므로
            // 바인딩 시점에는 accumulator 호출이 없어 lenient 처리
            org.mockito.Mockito.lenient().when(accumulator.getMaxBufferSize()).thenReturn(0);
            org.mockito.Mockito.lenient().when(accumulator.getBufferSize()).thenReturn(0);

            MeterRegistry registry = new SimpleMeterRegistry();
            SearchLogBatchMeterBinder binder = new SearchLogBatchMeterBinder(accumulator);
            binder.bindTo(registry);

            // then: maxBufferSize > 0 조건 false → 0.0 반환 (0 나누기 방지)
            assertThat(registry.get("shop.search.log.buffer.fill.ratio").gauge().value())
                    .isEqualTo(0.0);
        }
    }
}
