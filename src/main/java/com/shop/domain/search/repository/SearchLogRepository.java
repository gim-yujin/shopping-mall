package com.shop.domain.search.repository;

import com.shop.domain.search.entity.SearchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    @Query(value = "SELECT search_keyword, COUNT(*) as cnt FROM search_logs WHERE searched_at > NOW() - INTERVAL '7 days' GROUP BY search_keyword ORDER BY cnt DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findPopularKeywords();

    /**
     * [BUG FIX] search_logs 테이블 무한 증가 방지를 위한 보존 기간 초과 로그 삭제.
     * search_logs는 INSERT만 수행하고 삭제/파티셔닝이 없어 데이터가 무한 증가한다.
     * findPopularKeywords()는 최근 7일만 조회하므로 그 이상의 데이터는 불필요하며,
     * 데이터 증가 시 GROUP BY 쿼리 비용이 선형 증가하여 성능 저하를 유발한다.
     *
     * 이 쿼리는 SearchLogCleanupScheduler에서 주기적으로 호출되어
     * 보존 기간(기본 30일)이 지난 로그를 배치 삭제한다.
     *
     * @param cutoffDate 이 날짜 이전의 로그를 삭제
     * @return 삭제된 행 수
     * @deprecated 전량 삭제는 대규모 WAL 급증·테이블 잠금을 유발한다.
     *             {@link #deleteBatchOlderThan(LocalDateTime, int)} 사용을 권장한다.
     */
    @Deprecated
    @Modifying
    @Query(value = "DELETE FROM search_logs WHERE searched_at < :cutoffDate", nativeQuery = true)
    int deleteLogsOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * [BUG FIX] 배치 단위 검색 로그 삭제.
     *
     * 기존 deleteLogsOlderThan()은 보존 기간 초과 로그를 한 번에 전량 삭제하므로,
     * 수백만 건이 누적된 상태에서 실행 시 다음 문제가 발생한다:
     *   1) 대규모 WAL(Write-Ahead Log) 급증 → 디스크 I/O 폭증
     *   2) 삭제 대상 행에 대한 Row-level lock 장시간 유지 → 동시 INSERT 지연
     *   3) autovacuum 부하 집중 → 이후 쿼리 성능 저하
     *
     * 해결: LIMIT으로 한 번에 삭제하는 행 수를 제한하여 WAL 크기와 잠금 시간을 분산한다.
     * ctid 기반 서브쿼리는 PostgreSQL에서 PK 없이도 효율적인 배치 삭제를 보장한다.
     *
     * SearchLogCleanupScheduler에서 반복 호출하여 반환값이 batchSize 미만이 될 때까지 삭제한다.
     *
     * @param cutoffDate 이 날짜 이전의 로그를 삭제
     * @param batchSize  한 번에 삭제할 최대 행 수
     * @return 실제 삭제된 행 수 (batchSize 미만이면 잔여 데이터 없음)
     */
    @Modifying
    @Query(value = "DELETE FROM search_logs WHERE log_id IN " +
            "(SELECT log_id FROM search_logs WHERE searched_at < :cutoffDate LIMIT :batchSize)",
            nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate,
                             @Param("batchSize") int batchSize);
}
