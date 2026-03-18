package com.shop.domain.search.service;

import com.shop.domain.search.entity.SearchLog;
import com.shop.domain.search.repository.SearchLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchLogRepository searchLogRepository;

    public SearchService(SearchLogRepository searchLogRepository) {
        this.searchLogRepository = searchLogRepository;
    }

    /**
     * 검색 로그를 비동기로 저장한다.
     * 이전: 검색 요청 스레드에서 동기 INSERT → 커넥션 풀 점유 + 응답 지연
     * 이후: 별도 스레드에서 INSERT → 검색 응답은 읽기 전용으로 즉시 반환
     */
    @Async("asyncExecutor")
    @Transactional
    public void logSearch(Long userId, String keyword, int resultCount, String ipAddress, String userAgent) {
        try {
            searchLogRepository.save(new SearchLog(userId, keyword, resultCount, ipAddress, userAgent));
        } catch (Exception e) {
            log.warn("검색 로그 저장에 실패했지만 요청 처리는 계속 진행합니다. keyword={}, userId={}", keyword, userId, e);
        }
    }

    // [Phase 10] sync = true: 스탬피드 방지 — 검색 페이지 진입 시 모든 사용자가 동일 키 조회
    @Cacheable(value = "popularKeywords", key = "'top10'", sync = true)
    public List<String> getPopularKeywords() {
        return searchLogRepository.findPopularKeywords().stream()
                .map(row -> (String) row[0])
                .collect(Collectors.toList());
    }
}
