package com.shop.global.metrics;

import com.shop.domain.search.service.SearchLogBatchAccumulator;
import com.shop.domain.search.service.SearchLogWalManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SearchLogBatchMeterBinder WAL 활성 시 브랜치 커버리지 테스트.
 *
 * <p>기존 MetricsBranchCoverageTest에서는 walManager=null(WAL 비활성)만 테스트했다.
 * 이 테스트는 walManager != null일 때 WAL 메트릭(bytes.written, recovered.count)
 * 등록 브랜치를 커버한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchLogBatchMeterBinderWalTest {

    @Mock private SearchLogBatchAccumulator accumulator;
    @Mock private SearchLogWalManager walManager;

    @Test
    @DisplayName("WAL 활성 시 — WAL 메트릭 2개가 추가 등록된다")
    void bindTo_withWalManager_registersWalGauges() {
        // given
        lenient().when(accumulator.getBufferSize()).thenReturn(0);
        lenient().when(accumulator.getMaxBufferSize()).thenReturn(1000);
        lenient().when(accumulator.getTotalAdded()).thenReturn(0L);
        lenient().when(accumulator.getTotalFlushed()).thenReturn(0L);
        lenient().when(accumulator.getTotalDropped()).thenReturn(0L);
        lenient().when(accumulator.getFlushCount()).thenReturn(0L);

        when(walManager.getWalBytesWritten()).thenReturn(1024L);
        when(walManager.getRecoveredCount()).thenReturn(5L);

        MeterRegistry registry = new SimpleMeterRegistry();
        SearchLogBatchMeterBinder binder = new SearchLogBatchMeterBinder(accumulator, walManager);

        // when
        binder.bindTo(registry);

        // then: WAL 메트릭이 등록되고 값이 올바르다
        assertThat(registry.get("shop.search.log.wal.bytes.written").gauge().value())
                .isEqualTo(1024.0);
        assertThat(registry.get("shop.search.log.wal.recovered.count").gauge().value())
                .isEqualTo(5.0);

        // 기존 7개 게이지도 함께 등록됨
        assertThat(registry.get("shop.search.log.buffer.size").gauge()).isNotNull();
    }
}
