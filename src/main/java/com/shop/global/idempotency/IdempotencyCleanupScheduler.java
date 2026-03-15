package com.shop.global.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 만료된 멱등성 레코드를 배치 삭제하는 스케줄러.
 *
 * <h3>왜 정리가 필요한가?</h3>
 * <p>멱등성 레코드는 INSERT-only로 증가하므로, 보존 기간이 지난 레코드를
 * 주기적으로 삭제하지 않으면 테이블 크기가 무한 증가한다.
 * 보존 기간(기본 24시간)은 클라이언트 재시도 윈도우를 충분히 커버하면서도
 * 불필요한 데이터 축적을 방지하는 균형점이다.</p>
 *
 * <h3>배치 삭제 전략</h3>
 * <p>SearchLogCleanupScheduler와 동일한 패턴을 적용한다.
 * 한 번에 대량 삭제하면 WAL 급증과 잠금 경합이 발생하므로,
 * batchSize(기본 5,000)행씩 나눠 삭제하여 DB 부하를 분산한다.
 * 각 배치는 Repository 메서드의 독립 트랜잭션으로 실행되어
 * 중간 실패 시에도 이미 삭제된 배치는 유지된다.</p>
 */
@Component
public class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final IdempotencyCleanupExecutor cleanupExecutor;

    @Value("${app.idempotency.retention-hours:24}")
    private int retentionHours;

    @Value("${app.idempotency.cleanup-batch-size:5000}")
    private int batchSize;

    public IdempotencyCleanupScheduler(IdempotencyCleanupExecutor cleanupExecutor) {
        this.cleanupExecutor = cleanupExecutor;
    }

    /**
     * 매시간 정각에 보존 기간 초과 멱등성 레코드를 배치 삭제한다.
     *
     * <p>매일 1회가 아닌 매시간 실행하는 이유: 주문 트래픽이 집중되는 시간대에
     * 레코드가 빠르게 쌓일 수 있으므로, 자주 정리하여 테이블 크기를 작게 유지한다.
     * 정리 대상이 없으면 즉시 종료되므로 부하는 미미하다.</p>
     */
    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredRecords() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusHours(retentionHours);
        long startTime = System.nanoTime();

        int totalDeleted = 0;
        int batchCount = 0;

        try {
            int deleted;
            do {
                deleted = cleanupExecutor.deleteBatch(cutoffDate, batchSize);
                totalDeleted += deleted;
                batchCount++;
            } while (deleted >= batchSize);
        } catch (Exception e) {
            log.error("멱등성 레코드 정리 실패 - cutoffDate={}, completedBatches={}, deletedSoFar={}",
                    cutoffDate, batchCount, totalDeleted, e);
            return;
        }

        if (totalDeleted > 0) {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("멱등성 레코드 정리 완료 - cutoffDate={}, deletedRows={}, batches={}, elapsedMs={}",
                    cutoffDate, totalDeleted, batchCount, elapsedMs);
        }
    }
}
