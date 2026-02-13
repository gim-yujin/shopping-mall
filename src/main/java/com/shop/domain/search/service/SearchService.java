package com.shop.domain.search.service;

import com.shop.domain.search.entity.SearchLog;
import com.shop.domain.search.repository.SearchLogRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private final SearchLogRepository searchLogRepository;

    public SearchService(SearchLogRepository searchLogRepository) {
        this.searchLogRepository = searchLogRepository;
    }

    @Transactional
    public void logSearch(Long userId, String keyword, int resultCount, String ipAddress, String userAgent) {
        searchLogRepository.save(new SearchLog(userId, keyword, resultCount, ipAddress, userAgent));
    }

    @Cacheable(value = "popularKeywords", key = "'top10'")
    public List<String> getPopularKeywords() {
        return searchLogRepository.findPopularKeywords().stream()
                .map(row -> (String) row[0])
                .collect(Collectors.toList());
    }
}
