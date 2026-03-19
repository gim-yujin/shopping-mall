package com.shop.domain.search.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * [Phase 19] SearchLogBatchAccumulator 단위 테스트.
 *
 * <h3>검증 범위</h3>
 * <ul>
 *   <li>add() — 버퍼 추가, 오버플로우 보호, 메트릭 갱신</li>
 *   <li>flush() — 버퍼 drain, 배치 writer 호출, 에러 처리</li>
 *   <li>destroy() — 잔여 버퍼 플러시 (Graceful Shutdown)</li>
 *   <li>다중 배치 플러시 — batchSize 초과 시 여러 배치로 분할</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SearchLogBatchAccumulatorTest {

    @Mock
    private SearchLogBatchWriter writer;

    @Captor
    private ArgumentCaptor<List<SearchLogEntry>> batchCaptor;

    private SearchLogBatchAccumulator accumulator;

    private static final int BATCH_SIZE = 3;
    private static final int MAX_BUFFER_SIZE = 5;

    @BeforeEach
    void setUp() {
        accumulator = new SearchLogBatchAccumulator(writer, BATCH_SIZE, MAX_BUFFER_SIZE);
    }

    private SearchLogEntry createEntry(String keyword) {
        return new SearchLogEntry(1L, keyword, 10, "127.0.0.1", "JUnit", LocalDateTime.now());
    }

    // ──────────── add() 테스트 ────────────

    @Nested
    @DisplayName("add() — 버퍼 추가")
    class AddTests {

        @Test
        @DisplayName("정상 추가 — true 반환, bufferSize 증가, totalAdded 증가")
        void add_success() {
            boolean result = accumulator.add(createEntry("노트북"));

            assertThat(result).isTrue();
            assertThat(accumulator.getBufferSize()).isEqualTo(1);
            assertThat(accumulator.getTotalAdded()).isEqualTo(1);
        }

        @Test
        @DisplayName("여러 건 추가 — bufferSize 정확히 추적")
        void add_multipleEntries() {
            accumulator.add(createEntry("노트북"));
            accumulator.add(createEntry("키보드"));
            accumulator.add(createEntry("마우스"));

            assertThat(accumulator.getBufferSize()).isEqualTo(3);
            assertThat(accumulator.getTotalAdded()).isEqualTo(3);
        }

        @Test
        @DisplayName("버퍼 오버플로우 — false 반환, totalDropped 증가, bufferSize 불변")
        void add_overflow_returnsFalseAndIncrementsDropped() {
            // MAX_BUFFER_SIZE=5이므로 5건까지 추가 가능
            for (int i = 0; i < MAX_BUFFER_SIZE; i++) {
                assertThat(accumulator.add(createEntry("item" + i))).isTrue();
            }

            // 6번째 추가 시 오버플로우
            boolean overflowed = accumulator.add(createEntry("overflow"));

            assertThat(overflowed).isFalse();
            assertThat(accumulator.getBufferSize()).isEqualTo(MAX_BUFFER_SIZE);
            assertThat(accumulator.getTotalDropped()).isEqualTo(1);
            assertThat(accumulator.getTotalAdded()).isEqualTo(MAX_BUFFER_SIZE);
        }
    }

    // ──────────── flush() 테스트 ────────────

    @Nested
    @DisplayName("flush() — 배치 플러시")
    class FlushTests {

        @Test
        @DisplayName("빈 버퍼 — writer 호출 없음")
        void flush_emptyBuffer_noWriterCall() {
            accumulator.flush();

            verifyNoInteractions(writer);
            assertThat(accumulator.getFlushCount()).isZero();
        }

        @Test
        @DisplayName("batchSize 이하 — 단일 배치로 writer 호출")
        void flush_underBatchSize_singleBatch() {
            accumulator.add(createEntry("노트북"));
            accumulator.add(createEntry("키보드"));
            when(writer.writeBatch(anyList())).thenReturn(2);

            accumulator.flush();

            verify(writer).writeBatch(batchCaptor.capture());
            assertThat(batchCaptor.getValue()).hasSize(2);
            assertThat(accumulator.getBufferSize()).isZero();
            assertThat(accumulator.getTotalFlushed()).isEqualTo(2);
            assertThat(accumulator.getFlushCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("batchSize 초과 — 여러 배치로 분할 플러시")
        void flush_overBatchSize_multipleBatches() {
            // BATCH_SIZE=3, 5건 추가 → 배치 2회 (3건 + 2건)
            for (int i = 0; i < 5; i++) {
                accumulator.add(createEntry("item" + i));
            }
            when(writer.writeBatch(anyList())).thenAnswer(inv -> {
                List<?> batch = inv.getArgument(0);
                return batch.size();
            });

            accumulator.flush();

            verify(writer, times(2)).writeBatch(batchCaptor.capture());
            List<List<SearchLogEntry>> allBatches = batchCaptor.getAllValues();
            assertThat(allBatches.get(0)).hasSize(BATCH_SIZE);     // 첫 배치: 3건
            assertThat(allBatches.get(1)).hasSize(2);               // 두 번째 배치: 2건
            assertThat(accumulator.getBufferSize()).isZero();
            assertThat(accumulator.getTotalFlushed()).isEqualTo(5);
            assertThat(accumulator.getFlushCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("writer 예외 — 해당 배치 폐기, 다음 배치는 계속 처리")
        void flush_writerException_dropsFailedBatchAndContinues() {
            // 5건 추가, 첫 배치(3건) 실패, 두 번째 배치(2건) 성공
            for (int i = 0; i < 5; i++) {
                accumulator.add(createEntry("item" + i));
            }
            when(writer.writeBatch(anyList()))
                    .thenThrow(new RuntimeException("DB unavailable"))  // 첫 배치 실패
                    .thenReturn(2);                                      // 두 번째 배치 성공

            accumulator.flush();

            verify(writer, times(2)).writeBatch(anyList());
            assertThat(accumulator.getTotalDropped()).isEqualTo(BATCH_SIZE);  // 첫 배치 3건 폐기
            assertThat(accumulator.getTotalFlushed()).isEqualTo(2);            // 두 번째 배치 2건 성공
            assertThat(accumulator.getBufferSize()).isZero();
        }

        @Test
        @DisplayName("플러시 후 bufferSize 정확히 0")
        void flush_resetsBufferSize() {
            accumulator.add(createEntry("노트북"));
            when(writer.writeBatch(anyList())).thenReturn(1);

            accumulator.flush();

            assertThat(accumulator.getBufferSize()).isZero();
        }
    }

    // ──────────── destroy() 테스트 ────────────

    @Nested
    @DisplayName("destroy() — Graceful Shutdown")
    class DestroyTests {

        @Test
        @DisplayName("잔여 버퍼가 있으면 모두 플러시")
        void destroy_flushesRemainingEntries() {
            accumulator.add(createEntry("노트북"));
            accumulator.add(createEntry("키보드"));
            when(writer.writeBatch(anyList())).thenReturn(2);

            accumulator.destroy();

            verify(writer).writeBatch(batchCaptor.capture());
            assertThat(batchCaptor.getValue()).hasSize(2);
            assertThat(accumulator.getBufferSize()).isZero();
        }

        @Test
        @DisplayName("빈 버퍼 — writer 호출 없음")
        void destroy_emptyBuffer_noWriterCall() {
            assertThatCode(() -> accumulator.destroy()).doesNotThrowAnyException();
            verifyNoInteractions(writer);
        }
    }

    // ──────────── 메트릭 접근자 테스트 ────────────

    @Nested
    @DisplayName("메트릭 접근자")
    class MetricTests {

        @Test
        @DisplayName("설정값 반환")
        void configValues() {
            assertThat(accumulator.getBatchSize()).isEqualTo(BATCH_SIZE);
            assertThat(accumulator.getMaxBufferSize()).isEqualTo(MAX_BUFFER_SIZE);
        }

        @Test
        @DisplayName("초기 메트릭 0")
        void initialMetrics() {
            assertThat(accumulator.getBufferSize()).isZero();
            assertThat(accumulator.getTotalAdded()).isZero();
            assertThat(accumulator.getTotalFlushed()).isZero();
            assertThat(accumulator.getTotalDropped()).isZero();
            assertThat(accumulator.getFlushCount()).isZero();
        }
    }
}
