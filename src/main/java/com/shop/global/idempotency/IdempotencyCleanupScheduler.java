package com.shop.global.idempotency;

import com.shop.global.metrics.IdempotencyMetrics;
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
    private final IdempotencyService idempotencyService;
    private final IdempotencyMetrics idempotencyMetrics;

    @Value("${app.idempotency.retention-hours:24}")
    private int retentionHours;

    @Value("${app.idempotency.cleanup-batch-size:5000}")
    private int batchSize;

    /**
     * [Phase 14] PROCESSING 고착 레코드를 FAILED로 전환하기까지의 대기 시간 (분).
     *
     * <p>주문 생성 트랜잭션은 비관적 잠금 대기를 포함해도 통상 수 초 이내에 완료된다.
     * 5분 이상 PROCESSING 상태가 유지되면 서버 크래시 등 비정상 종료로 간주한다.
     * 이 값을 너무 짧게 설정하면 정상 처리 중인 요청이 FAILED로 전환될 수 있고,
     * 너무 길게 설정하면 클라이언트가 오래 대기해야 한다.</p>
     */
    @Value("${app.idempotency.stale-timeout-minutes:5}")
    private int staleTimeoutMinutes;

    public IdempotencyCleanupScheduler(IdempotencyCleanupExecutor cleanupExecutor,
                                        IdempotencyService idempotencyService,
                                        IdempotencyMetrics idempotencyMetrics) {
        this.cleanupExecutor = cleanupExecutor;
        this.idempotencyService = idempotencyService;
        this.idempotencyMetrics = idempotencyMetrics;
    }

    /**
     * [Phase 14] 매분 PROCESSING 고착 레코드를 FAILED로 전환한다.
     *
     * <p>서버 크래시 후 PROCESSING 레코드가 5분 이상 고착되면
     * 클라이언트는 409 Conflict을 무한 수신하게 된다.
     * 매분 실행하여 고착 레코드를 빠르게 감지하고 복구한다.
     * 복구 대상이 없으면 즉시 종료되므로 부하는 미미하다.</p>
     */
    @Scheduled(cron = "0 * * * * *")
    public void recoverStaleProcessingRecords() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(staleTimeoutMinutes);
        int recovered = idempotencyService.recoverStaleProcessing(cutoffTime);
        if (recovered > 0) {
            // [Phase 14] Prometheus에서 stale 복구 빈도를 모니터링하여
            // 배포 실패나 OOM 빈도를 정량적으로 파악할 수 있다
            idempotencyMetrics.recordStaleRecovered(recovered);
        }
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
