package com.shop.domain.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SearchLogBatchAccumulator WAL 연동 브랜치 테스트.
 *
 * <p>기존 SearchLogBatchAccumulatorTest는 WAL 비활성 상태만 테스트한다.
 * 이 테스트는 WAL 활성 시의 add/flush/destroy 브랜치를 커버한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchLogBatchAccumulatorWalTest {

    @Mock private SearchLogBatchWriter writer;
    @Mock private SearchLogWalManager walManager;

    private SearchLogEntry sampleEntry() {
        return new SearchLogEntry(1L, "키워드", 10, "127.0.0.1", "Mozilla", LocalDateTime.now());
    }

    /**
     * WAL 활성 accumulator를 생성한다.
     * 생성자에서 walManager.getWalDir()을 호출하므로 먼저 stub 설정.
     */
    private SearchLogBatchAccumulator createWalAccumulator() {
        lenient().when(walManager.getWalDir()).thenReturn(Path.of("/tmp/wal"));
        return new SearchLogBatchAccumulator(writer, 500, 10000, walManager);
    }

    @Test
    @DisplayName("add — WAL 활성 시 walManager.append()를 호출한다")
    void add_withWal_callsWalAppend() {
        SearchLogBatchAccumulator accumulator = createWalAccumulator();

        SearchLogEntry entry = sampleEntry();
        accumulator.add(entry);

        verify(walManager).append(entry);
        assertThat(accumulator.getBufferSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("add — WAL 비활성(null) 시 walManager 호출 없이 버퍼에만 추가한다")
    void add_withoutWal_skipsWalAppend() {
        SearchLogBatchAccumulator accumulator =
                new SearchLogBatchAccumulator(writer, 500, 10000);

        accumulator.add(sampleEntry());

        assertThat(accumulator.getBufferSize()).isEqualTo(1);
        assertThat(accumulator.getWalManager()).isNull();
    }

    @Test
    @DisplayName("flush — WAL 활성 시 rotateSegment + deleteSegment를 호출한다")
    void flush_withWal_rotatesAndDeletesSegment() {
        SearchLogBatchAccumulator accumulator = createWalAccumulator();

        Path segmentPath = Path.of("/tmp/wal/wal-123.log");
        when(walManager.rotateSegment()).thenReturn(segmentPath);

        accumulator.add(sampleEntry());
        when(writer.writeBatch(anyList())).thenReturn(1);
        accumulator.flush();

        verify(walManager).rotateSegment();
        verify(walManager).deleteSegment(segmentPath);
    }

    @Test
    @DisplayName("flush — WAL rotateSegment()가 null 반환(빈 세그먼트) → deleteSegment 미호출")
    void flush_walReturnsNull_skipsDelete() {
        SearchLogBatchAccumulator accumulator = createWalAccumulator();
        when(walManager.rotateSegment()).thenReturn(null);

        accumulator.flush();

        verify(walManager).rotateSegment();
        verify(walManager, never()).deleteSegment(any());
    }

    @Test
    @DisplayName("destroy — 잔여 버퍼 flush 후 WAL close()를 호출한다")
    void destroy_withWal_closesWalManager() {
        SearchLogBatchAccumulator accumulator = createWalAccumulator();
        lenient().when(walManager.rotateSegment()).thenReturn(null);

        accumulator.add(sampleEntry());
        when(writer.writeBatch(anyList())).thenReturn(1);

        accumulator.destroy();

        verify(walManager).close();
    }

    @Test
    @DisplayName("destroy — WAL 비활성 시 close() 미호출")
    void destroy_withoutWal_skipsClose() {
        SearchLogBatchAccumulator accumulator =
                new SearchLogBatchAccumulator(writer, 500, 10000);

        accumulator.destroy();

        assertThat(accumulator.getWalManager()).isNull();
    }

    @Test
    @DisplayName("getWalManager — WAL 활성 시 인스턴스를 반환한다")
    void getWalManager_withWal_returnsInstance() {
        SearchLogBatchAccumulator accumulator = createWalAccumulator();

        assertThat(accumulator.getWalManager()).isSameAs(walManager);
    }

    @Test
    @DisplayName("scheduledFlush — flush()를 호출한다")
    void scheduledFlush_callsFlush() {
        SearchLogBatchAccumulator accumulator =
                new SearchLogBatchAccumulator(writer, 500, 10000);

        accumulator.scheduledFlush();

        assertThat(accumulator.getFlushCount()).isZero();
    }
}
