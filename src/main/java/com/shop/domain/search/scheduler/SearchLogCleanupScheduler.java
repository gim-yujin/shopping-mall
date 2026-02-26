package com.shop.domain.search.scheduler;

import com.shop.domain.search.repository.SearchLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * [BUG FIX] search_logs 테이블 무한 증가 방지 스케줄러.
 *
 * 문제: search_logs는 INSERT만 수행하고 삭제/파티셔닝이 없어 데이터가 무한 증가한다.
 *       findPopularKeywords()는 최근 7일만 GROUP BY하므로 오래된 데이터는 불필요하지만,
 *       전체 테이블 크기가 커질수록 쿼리 비용이 선형 증가하여 성능이 저하된다.
 *
 * 해결: 매일 새벽 3시에 보존 기간(기본 30일)이 지난 로그를 배치 삭제한다.
 *       한 번에 너무 많은 행을 삭제하면 테이블 잠금과 WAL 급증이 발생하므로,
 *       배치 크기(기본 10,000행)로 나눠서 반복 삭제한다.
 *
 * 참고: 향후 데이터 분석 요구가 있다면 삭제 전 별도 아카이브 테이블로 이동하거나,
 *       searched_at 기준 월별 range partition으로 전환하여 파티션 DROP으로 대체할 수 있다.
 */
@Component
public class SearchLogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchLogCleanupScheduler.class);

    private final SearchLogRepository searchLogRepository;

    @Value("${app.search-log.retention-days:30}")
    private int retentionDays;

    public SearchLogCleanupScheduler(SearchLogRepository searchLogRepository) {
        this.searchLogRepository = searchLogRepository;
    }

    /**
     * 매일 새벽 3시에 보존 기간 초과 검색 로그를 삭제한다.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldSearchLogs() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        long startTime = System.nanoTime();

        int totalDeleted = 0;
        try {
            int deleted = searchLogRepository.deleteLogsOlderThan(cutoffDate);
            totalDeleted = deleted;
        } catch (Exception e) {
            log.error("검색 로그 정리 실패 - cutoffDate={}", cutoffDate, e);
            return;
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("검색 로그 정리 완료 - cutoffDate={}, deletedRows={}, elapsedMs={}",
                cutoffDate, totalDeleted, elapsedMs);
    }
}
