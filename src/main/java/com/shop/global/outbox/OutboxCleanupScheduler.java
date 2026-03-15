package com.shop.global.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 처리 완료된 Outbox 이벤트를 배치 삭제하는 스케줄러.
 *
 * <h3>왜 정리가 필요한가?</h3>
 * <p>Outbox 테이블은 INSERT-only로 증가한다. 처리 완료(PROCESSED) 이벤트를
 * 주기적으로 삭제하지 않으면 테이블 크기가 무한 증가하여
 * 폴러의 PENDING 조회 성능에 영향을 줄 수 있다.</p>
 *
 * <p>partial index(idx_outbox_pending)가 PENDING 조회를 보호하지만,
 * PostgreSQL autovacuum 부하와 디스크 사용량 관리를 위해
 * 오래된 레코드를 정리하는 것이 운영상 바람직하다.</p>
 *
 * <h3>보존 기간</h3>
 * <p>기본 7일 보존: PROCESSED 이벤트를 7일 동안 유지하여
 * 디버깅과 감사(audit) 목적으로 최근 이벤트 이력을 조회할 수 있도록 한다.
 * 7일이 지난 이벤트는 매일 새벽 4시에 배치 삭제된다.</p>
 */
@Component
public class OutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduler.class);

    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.outbox.retention-days:7}")
    private int retentionDays;

    @Value("${app.outbox.cleanup-batch-size:5000}")
    private int batchSize;

    public OutboxCleanupScheduler(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * 매일 새벽 4시에 보존 기간 초과 PROCESSED 이벤트를 배치 삭제한다.
     *
     * <p>SearchLogCleanupScheduler, IdempotencyCleanupScheduler와 동일한
     * 배치 삭제 패턴을 적용하여 WAL 크기와 잠금 시간을 분산한다.</p>
     *
     * <p>별도 Executor 빈 분리 없이 @Transactional을 직접 사용하는 이유:
     * 배치 삭제 쿼리가 네이티브 @Modifying이므로 Repository 메서드 자체에
     * 트랜잭션 경계가 형성된다. 스케줄러의 루프는 반복 호출만 담당하고,
     * 각 배치는 Repository 호출 단위로 독립 커밋된다.</p>
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void cleanupProcessedEvents() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        long startTime = System.nanoTime();

        int totalDeleted = 0;
        int batchCount = 0;

        try {
            int deleted;
            do {
                deleted = deleteOneBatch(cutoffDate);
                totalDeleted += deleted;
                batchCount++;
            } while (deleted >= batchSize);
        } catch (Exception e) {
            log.error("Outbox 이벤트 정리 실패 - cutoffDate={}, completedBatches={}, deletedSoFar={}",
                    cutoffDate, batchCount, totalDeleted, e);
            return;
        }

        if (totalDeleted > 0) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Outbox 이벤트 정리 완료 - cutoffDate={}, deletedRows={}, batches={}, elapsedMs={}",
                    cutoffDate, totalDeleted, batchCount, elapsedMs);
        }
    }

    @Transactional
    public int deleteOneBatch(LocalDateTime cutoffDate) {
        return outboxEventRepository.deleteBatchProcessedBefore(cutoffDate, batchSize);
    }
}
