package com.shop.global.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OutboxCleanupScheduler 단위 테스트.
 *
 * <p>Outbox 테이블의 PROCESSED/DEAD_LETTER 이벤트를 배치 삭제하는 스케줄러의
 * 모든 분기를 검증한다. 배치 삭제 루프, 예외 처리, 삭제 건수=0 분기를 커버한다.</p>
 *
 * <p>스케줄러는 @Scheduled(cron)으로 실행되지만, 테스트에서는 메서드를 직접 호출하여
 * 스케줄링 인프라 없이 비즈니스 로직만 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxCleanupSchedulerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private OutboxCleanupScheduler scheduler;

    // ── cleanupProcessedEvents ──

    @Nested
    @DisplayName("cleanupProcessedEvents — PROCESSED 이벤트 배치 삭제")
    class CleanupProcessedTests {

        @Test
        @DisplayName("삭제 대상이 없으면 로그 없이 정상 종료")
        void nothingToDelete_completesQuietly() {
            // batchSize=5000 기본값 설정
            ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            // 첫 배치에서 0건 삭제 → do-while 루프 1회 실행 후 종료
            when(outboxEventRepository.deleteBatchProcessedBefore(any(), eq(5000)))
                    .thenReturn(0);

            scheduler.cleanupProcessedEvents();

            // deleteBatchProcessedBefore가 정확히 1번 호출됨 (do-while 최소 1회)
            verify(outboxEventRepository, times(1))
                    .deleteBatchProcessedBefore(any(), eq(5000));
        }

        @Test
        @DisplayName("단일 배치로 삭제 완료 — batchSize 미만 건수 삭제")
        void singleBatch_deletesAndCompletes() {
            ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            // 첫 배치: 3000건(< batchSize) 삭제 → 루프 종료
            when(outboxEventRepository.deleteBatchProcessedBefore(any(), eq(5000)))
                    .thenReturn(3000);

            scheduler.cleanupProcessedEvents();

            verify(outboxEventRepository, times(1))
                    .deleteBatchProcessedBefore(any(), eq(5000));
        }

        @Test
        @DisplayName("다중 배치 삭제 — batchSize 이상이면 루프 반복")
        void multipleBatches_loopsUntilLastBatchIsSmall() {
            ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
            ReflectionTestUtils.setField(scheduler, "batchSize", 1000);

            // 1차: 1000건(= batchSize) → 2차: 1000건(= batchSize) → 3차: 500건(< batchSize) → 종료
            when(outboxEventRepository.deleteBatchProcessedBefore(any(), eq(1000)))
                    .thenReturn(1000, 1000, 500);

            scheduler.cleanupProcessedEvents();

            // do-while 루프가 3회 반복 (총 2500건 삭제)
            verify(outboxEventRepository, times(3))
                    .deleteBatchProcessedBefore(any(), eq(1000));
        }

        @Test
        @DisplayName("예외 발생 시 로그 남기고 정상 종료 — 다른 스케줄러에 영향 없음")
        void exception_logsAndReturns() {
            ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            // 삭제 중 DB 예외 발생
            when(outboxEventRepository.deleteBatchProcessedBefore(any(), eq(5000)))
                    .thenThrow(new RuntimeException("DB connection lost"));

            // 예외가 밖으로 전파되지 않고 내부에서 catch됨
            scheduler.cleanupProcessedEvents();

            verify(outboxEventRepository, times(1))
                    .deleteBatchProcessedBefore(any(), eq(5000));
        }
    }

    // ── cleanupDeadLetterEvents ──

    @Nested
    @DisplayName("cleanupDeadLetterEvents — DEAD_LETTER 이벤트 배치 삭제")
    class CleanupDeadLetterTests {

        @Test
        @DisplayName("삭제 대상이 없으면 정상 종료")
        void nothingToDelete_completesQuietly() {
            ReflectionTestUtils.setField(scheduler, "deadLetterRetentionDays", 30);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            when(outboxEventRepository.deleteBatchDeadLetterBefore(any(), eq(5000)))
                    .thenReturn(0);

            scheduler.cleanupDeadLetterEvents();

            verify(outboxEventRepository, times(1))
                    .deleteBatchDeadLetterBefore(any(), eq(5000));
        }

        @Test
        @DisplayName("단일 배치로 삭제 완료")
        void singleBatch_deletesAndCompletes() {
            ReflectionTestUtils.setField(scheduler, "deadLetterRetentionDays", 30);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            when(outboxEventRepository.deleteBatchDeadLetterBefore(any(), eq(5000)))
                    .thenReturn(100);

            scheduler.cleanupDeadLetterEvents();

            verify(outboxEventRepository, times(1))
                    .deleteBatchDeadLetterBefore(any(), eq(5000));
        }

        @Test
        @DisplayName("다중 배치 삭제 — batchSize 이상이면 루프 반복")
        void multipleBatches_loopsUntilDone() {
            ReflectionTestUtils.setField(scheduler, "deadLetterRetentionDays", 30);
            ReflectionTestUtils.setField(scheduler, "batchSize", 100);

            // 1차: 100건 → 2차: 50건(< batchSize) → 종료
            when(outboxEventRepository.deleteBatchDeadLetterBefore(any(), eq(100)))
                    .thenReturn(100, 50);

            scheduler.cleanupDeadLetterEvents();

            verify(outboxEventRepository, times(2))
                    .deleteBatchDeadLetterBefore(any(), eq(100));
        }

        @Test
        @DisplayName("예외 발생 시 로그 남기고 정상 종료")
        void exception_logsAndReturns() {
            ReflectionTestUtils.setField(scheduler, "deadLetterRetentionDays", 30);
            ReflectionTestUtils.setField(scheduler, "batchSize", 5000);

            when(outboxEventRepository.deleteBatchDeadLetterBefore(any(), eq(5000)))
                    .thenThrow(new RuntimeException("DB timeout"));

            scheduler.cleanupDeadLetterEvents();

            verify(outboxEventRepository, times(1))
                    .deleteBatchDeadLetterBefore(any(), eq(5000));
        }
    }
}
