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
     */
    @Modifying
    @Query(value = "DELETE FROM search_logs WHERE searched_at < :cutoffDate", nativeQuery = true)
    int deleteLogsOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);
}
