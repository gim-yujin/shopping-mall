package com.shop.domain.search.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * [BUG FIX] search_logs 테이블 무한 증가 방지 스케줄러.
 *
 * 문제: search_logs는 INSERT만 수행하고 삭제/파티셔닝이 없어 데이터가 무한 증가한다.
 *       findPopularKeywords()는 최근 7일만 GROUP BY하므로 오래된 데이터는 불필요하지만,
 *       전체 테이블 크기가 커질수록 쿼리 비용이 선형 증가하여 성능이 저하된다.
 *
 * 해결: 매일 새벽 3시에 보존 기간(기본 30일)이 지난 로그를 배치 삭제한다.
 *
 * [BUG FIX] 기존 구현은 Javadoc에 "배치 크기로 나눠서 반복 삭제"라고 기술하였으나,
 * 실제로는 DELETE ... WHERE searched_at < :cutoffDate 한 방 쿼리를 실행하였다.
 * 수백만 건이 누적된 상태에서 한 번에 삭제하면:
 *   - 대규모 WAL(Write-Ahead Log) 급증 → 디스크 I/O 폭증, 복제 지연
 *   - 삭제 대상 행의 Row-level lock 장시간 유지 → 동시 INSERT 지연
 *   - autovacuum 부하 집중 → 이후 SELECT 쿼리 성능 저하
 *
 * 수정: deleteBatchOlderThan(cutoffDate, batchSize)을 반복 호출하여
 * 한 번에 batchSize(기본 10,000)행씩 삭제함으로써 WAL 크기와 잠금 시간을 분산한다.
 * 각 배치는 독립 트랜잭션으로 실행되어 중간 실패 시에도
 * 이미 삭제된 배치는 유지되고, 다음 실행 때 나머지를 이어서 처리한다.
 *
 * [설계 결정] 배치 삭제 메서드를 별도 컴포넌트(SearchLogCleanupExecutor)로 분리한 이유:
 * Spring AOP는 프록시 기반이므로, 같은 클래스 내에서 this.deleteBatch()를 호출하면
 * @Transactional 어노테이션이 적용되지 않는다 (self-invocation 문제).
 * 배치별 독립 트랜잭션을 보장하기 위해 실제 삭제 로직을 별도 빈으로 분리하여
 * Spring 프록시를 통한 정상적인 트랜잭션 경계를 확보한다.
 *
 * 참고: 향후 데이터 분석 요구가 있다면 삭제 전 별도 아카이브 테이블로 이동하거나,
 *       searched_at 기준 월별 range partition으로 전환하여 파티션 DROP으로 대체할 수 있다.
 */
@Component
public class SearchLogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchLogCleanupScheduler.class);

    private final SearchLogCleanupExecutor cleanupExecutor;

    @Value("${app.search-log.retention-days:30}")
    private int retentionDays;

    @Value("${app.search-log.cleanup-batch-size:10000}")
    private int batchSize;

    public SearchLogCleanupScheduler(SearchLogCleanupExecutor cleanupExecutor) {
        this.cleanupExecutor = cleanupExecutor;
    }

    /**
     * 매일 새벽 3시에 보존 기간 초과 검색 로그를 배치 단위로 반복 삭제한다.
     *
     * 각 배치 삭제는 Executor의 독립 트랜잭션으로 실행된다:
     *   - 배치 단위로 WAL 크기와 잠금 시간을 분산
     *   - 중간 실패 시 이미 삭제된 배치는 커밋 상태 유지
     *   - 다음 스케줄 실행 시 잔여 데이터를 이어서 삭제
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldSearchLogs() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        long startTime = System.nanoTime();

        int totalDeleted = 0;
        int batchCount = 0;

        try {
            int deleted;
            do {
                // [핵심] 별도 빈(cleanupExecutor)을 통해 호출하여
                // Spring AOP 프록시의 @Transactional이 정상 적용되도록 보장한다.
                deleted = cleanupExecutor.deleteBatch(cutoffDate, batchSize);
                totalDeleted += deleted;
                batchCount++;

                if (deleted > 0 && log.isDebugEnabled()) {
                    log.debug("검색 로그 배치 삭제 - batch={}, deleted={}, totalSoFar={}",
                            batchCount, deleted, totalDeleted);
                }
            } while (deleted >= batchSize);
        } catch (Exception e) {
            log.error("검색 로그 정리 실패 - cutoffDate={}, completedBatches={}, deletedSoFar={}",
                    cutoffDate, batchCount, totalDeleted, e);
            return;
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("검색 로그 정리 완료 - cutoffDate={}, deletedRows={}, batches={}, elapsedMs={}",
                cutoffDate, totalDeleted, batchCount, elapsedMs);
    }
}
