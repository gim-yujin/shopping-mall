package com.shop.domain.search.repository;

import com.shop.domain.search.entity.SearchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    @Query(value = "SELECT search_keyword, COUNT(*) as cnt FROM search_logs WHERE searched_at > NOW() - INTERVAL '7 days' GROUP BY search_keyword ORDER BY cnt DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findPopularKeywords();
}
