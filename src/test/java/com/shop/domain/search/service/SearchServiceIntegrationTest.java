package com.shop.domain.search.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 19] SearchService 통합 테스트 — 배치 쓰기 패턴 검증.
 *
 * <h3>기존 대비 변경사항</h3>
 * <p>기존: {@code @Async} 비동기 실행을 Awaitility로 폴링 대기하여 DB 저장을 검증했다.</p>
 * <p>변경: {@code logSearch()}가 동기적으로 버퍼에 추가하고,
 * 명시적 {@code accumulator.flush()} 호출로 즉시 DB에 저장한 뒤 검증한다.
 * 비동기 폴링이 불필요하여 테스트가 더 결정적(deterministic)이다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class SearchServiceIntegrationTest {

    @Autowired
    private SearchService searchService;

    // [Phase 19] 배치 누적기를 직접 주입하여 테스트에서 명시적 flush 가능
    @Autowired
    private SearchLogBatchAccumulator batchAccumulator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String keywordForCleanup;

    @AfterEach
    void tearDown() {
        if (keywordForCleanup != null) {
            jdbcTemplate.update("DELETE FROM search_logs WHERE search_keyword = ?", keywordForCleanup);
        }
    }

    @Test
    @DisplayName("logSearch + flush — 배치 누적기를 통해 search_logs에 저장")
    void logSearch_persistsRowViaBatchFlush() {
        keywordForCleanup = "TEST_BATCH_SEARCH_" + System.currentTimeMillis();
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                Integer.class, keywordForCleanup);

        // [Phase 19] search_logs.user_id는 users(user_id) FK를 참조하므로 유효한 ID 필요
        Long validUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true LIMIT 1",
                Long.class);

        // logSearch()는 동기적으로 버퍼에 추가 (기존: @Async 비동기 제출)
        searchService.logSearch(validUserId, keywordForCleanup, 5, "127.0.0.1", "JUnit-Integration");

        // [Phase 19] 명시적 flush로 즉시 DB 저장 — 기존 Awaitility 폴링 불필요
        batchAccumulator.flush();

        int current = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                Integer.class, keywordForCleanup);

        assertThat(current)
                .as("배치 플러시 후 검색 로그가 DB에 저장되어야 함 — keyword=%s", keywordForCleanup)
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("getPopularKeywords - 최대 10개의 키워드를 반환")
    void getPopularKeywords_returnsAtMostTenKeywords() {
        List<String> keywords = searchService.getPopularKeywords();

        assertThat(keywords)
                .as("인기 키워드 목록은 null이 아니어야 함")
                .isNotNull();
        assertThat(keywords)
                .as("인기 키워드는 최대 10개여야 함")
                .hasSizeLessThanOrEqualTo(10);
    }
}
