package com.shop.domain.search.service;

import com.shop.domain.search.repository.SearchLogRepository;
import com.shop.domain.search.service.broker.SearchLogStreamProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchLogRepository searchLogRepository;
    private final SearchLogBatchAccumulator batchAccumulator;
    private final Optional<SearchLogStreamProducer> streamProducer;

    public SearchService(SearchLogRepository searchLogRepository,
                         SearchLogBatchAccumulator batchAccumulator,
                         @Autowired(required = false) SearchLogStreamProducer streamProducer) {
        this.searchLogRepository = searchLogRepository;
        this.batchAccumulator = batchAccumulator;
        this.streamProducer = Optional.ofNullable(streamProducer);
    }

    /**
     * 검색 로그를 배치 누적기에 추가한다.
     *
     * <p>[Phase 19] 기존 방식 vs 배치 방식 비교:</p>
     *
     * <p><b>기존 (Phase 6):</b> {@code @Async("asyncExecutor")} + {@code @Transactional}으로
     * 비동기 실행하여 HTTP 스레드를 해방했지만, 검색 건마다 개별 INSERT 1회 + 트랜잭션 1회가 발생.
     * asyncExecutor 큐(500)를 검색 로그가 점유하여 다른 비동기 작업에 영향.</p>
     *
     * <p><b>개선 (Phase 19):</b> {@code SearchLogBatchAccumulator.add()}는
     * lock-free CAS 연산(ConcurrentLinkedQueue.offer())으로 즉시 반환된다.
     * {@code @Async}가 불필요 — 스레드 풀 큐 제출 비용 자체가 제거되고,
     * HTTP 스레드 블로킹이 원천적으로 없다.
     * 누적된 로그는 주기적(5초) 배치 INSERT로 한 번에 저장되어
     * DB 라운드트립이 1000:1 → 2:1 비율로 감소한다.</p>
     *
     * <p>버퍼 오버플로우 시 로그는 조용히 폐기된다.
     * 기존 asyncExecutor DiscardPolicy와 동일한 정책이며,
     * 검색 로그는 인기 검색어 통계 목적이므로 일부 유실이 서비스 품질에 영향 없다.</p>
     */
    public void logSearch(Long userId, String keyword, int resultCount,
                          String ipAddress, String userAgent) {
        // [Phase 19] searchedAt을 추가 시점에 캡처하여 실제 검색 시각을 보존한다.
        // 플러시 시점(최대 5초 후)이 아닌 검색 발생 시점이 기록되어야
        // 인기 검색어 시간대별 분석의 정확도가 유지된다.
        SearchLogEntry entry = new SearchLogEntry(
                userId, keyword, resultCount, ipAddress, userAgent, LocalDateTime.now());

        // [Phase 21] Redis Streams 브로커가 활성화돼 있으면 우선 사용. Redis 일시 장애 등으로
        // XADD 가 실패하면 기존 인메모리 + WAL 경로로 폴백한다(검색 응답은 절대 막지 않음).
        if (streamProducer.isPresent()) {
            try {
                streamProducer.get().produce(entry);
                return;
            } catch (RuntimeException e) {
                log.warn("[Phase 21] 스트림 적재 실패 — 인메모리 경로로 폴백. keyword={}", keyword, e);
            }
        }

        if (!batchAccumulator.add(entry)) {
            log.warn("[Phase 19] 검색 로그 버퍼 오버플로우 — keyword={}, bufferSize={}",
                    keyword, batchAccumulator.getBufferSize());
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
