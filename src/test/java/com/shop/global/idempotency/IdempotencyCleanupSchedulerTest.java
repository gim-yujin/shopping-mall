package com.shop.global.idempotency;

import com.shop.global.metrics.IdempotencyMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotencyCleanupScheduler 단위 테스트.
 *
 * <p>기존 멱등성 테스트에서 스케줄러 로직은 커버되지 않았다 (LINE 21%, BRANCH 0%).
 * 이 테스트에서 다음 분기를 검증한다:
 * - recoverStaleProcessingRecords: recovered > 0 → 메트릭 기록 / recovered == 0 → 건너뜀
 * - cleanupExpiredRecords: do-while 루프 (단일 배치, 다중 배치, 빈 결과)
 * - cleanupExpiredRecords: 예외 발생 시 로그 후 return
 * - cleanupExpiredRecords: totalDeleted > 0 → 로그 / totalDeleted == 0 → 건너뜀</p>
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupSchedulerTest {

    @Mock
    private IdempotencyCleanupExecutor cleanupExecutor;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private IdempotencyMetrics idempotencyMetrics;

    @InjectMocks
    private IdempotencyCleanupScheduler scheduler;

    // ── recoverStaleProcessingRecords ──

    @Nested
    @DisplayName("recoverStaleProcessingRecords — PROCESSING 고착 복구")
    class RecoverStaleTests {

        @Test
        @DisplayName("복구된 레코드가 있으면 메트릭에 기록한다")
        void recovered_recordsMetric() {
            // given: 5분 이상 고착된 레코드 3건 복구
            ReflectionTestUtils.setField(scheduler, "staleTimeoutMinutes", 5);
            when(idempotencyService.recoverStaleProcessing(any())).thenReturn(3);

            // when
            scheduler.recoverStaleProcessingRecords();

            // then: recovered > 0 → 메트릭 기록
            verify(idempotencyMetrics).recordStaleRecovered(3);
        }

        @Test
        @DisplayName("복구할 레코드가 없으면 메트릭을 건너뛴다")
        void noRecovery_skipsMetric() {
            // given: 고착 레코드 없음
            ReflectionTestUtils.setField(scheduler, "staleTimeoutMinutes", 5);
            when(idempotencyService.recoverStaleProcessing(any())).thenReturn(0);

            // when
            scheduler.recoverStaleProcessingRecords();

            // then: recovered == 0 → 메트릭 호출 없음
            verify(idempotencyMetrics, never()).recordStaleRecovered(anyInt());
        }
    }

    // ── cleanupExpiredRecords ──

    @Nested
    @DisplayName("cleanupExpiredRecords — 만료 레코드 배치 삭제")
    class CleanupTests {

        @Test
        @DisplayName("삭제할 레코드가 없으면 즉시 종료")
        void nothingToDelete_returnsImmediately() {
            // given: 첫 배치에서 0건 삭제 → do-while 루프 1회 후 종료
            ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);
            when(cleanupExecutor.deleteBatch(any(), eq(5000))).thenReturn(0);

            // when
            scheduler.cleanupExpiredRecords();

            // then: 1회만 호출 (do-while은 최소 1회 실행)
            verify(cleanupExecutor, times(1)).deleteBatch(any(), eq(5000));
        }

        @Test
        @DisplayName("단일 배치로 모든 레코드 삭제 (삭제 건수 < batchSize)")
        void singleBatch_deletesAll() {
            // given: 첫 배치에서 3000건 삭제 (< batchSize 5000) → 루프 종료
            ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);
            when(cleanupExecutor.deleteBatch(any(), eq(5000))).thenReturn(3000);

            // when
            scheduler.cleanupExpiredRecords();

            // then: 1회 호출 후 종료 (deleted < batchSize)
            verify(cleanupExecutor, times(1)).deleteBatch(any(), eq(5000));
        }

        @Test
        @DisplayName("다중 배치 삭제 (do-while 루프 반복)")
        void multipleBatches_loopsUntilExhausted() {
            // given: 첫 번째 배치 5000건(= batchSize) → 두 번째 배치 2000건 → 종료
            // do-while 조건: deleted >= batchSize → true → 반복
            ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);
            when(cleanupExecutor.deleteBatch(any(), eq(5000)))
                    .thenReturn(5000)  // 1차: batchSize만큼 삭제 → 루프 계속
                    .thenReturn(2000); // 2차: batchSize 미만 → 루프 종료

            // when
            scheduler.cleanupExpiredRecords();

            // then: 2회 호출
            verify(cleanupExecutor, times(2)).deleteBatch(any(), eq(5000));
        }

        @Test
        @DisplayName("예외 발생 시 로그 후 즉시 반환")
        void exception_logsAndReturns() {
            // given: 첫 배치 성공, 두 번째 배치에서 예외 발생
            ReflectionTestUtils.setField(scheduler, "retentionHours", 24);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);
            when(cleanupExecutor.deleteBatch(any(), eq(5000)))
                    .thenReturn(5000)  // 1차: 성공
                    .thenThrow(new RuntimeException("DB connection lost")); // 2차: 예외

            // when: 예외가 외부로 전파되지 않아야 함
            scheduler.cleanupExpiredRecords();

            // then: 2회 호출 (1차 성공, 2차 예외) — 예외 후 즉시 return
            verify(cleanupExecutor, times(2)).deleteBatch(any(), eq(5000));
        }
    }
}
