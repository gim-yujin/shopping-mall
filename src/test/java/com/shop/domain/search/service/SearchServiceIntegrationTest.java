package com.shop.domain.search.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class SearchServiceIntegrationTest {

    @Autowired
    private SearchService searchService;

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
    @DisplayName("logSearch - search_logs에 검색 로그가 저장됨")
    void logSearch_persistsRow() {
        keywordForCleanup = "TEST_SEARCH_" + System.currentTimeMillis();
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                Integer.class, keywordForCleanup);

        // [FIX] 기존 코드는 userId=11L을 하드코딩했으나, test-reset.sql이
        // 스키마를 완전히 초기화(DROP SCHEMA CASCADE)하므로 user_id=11인 사용자가 존재하지 않는다.
        // search_logs.user_id는 users(user_id)를 참조하는 FK이므로,
        // 존재하지 않는 userId를 전달하면 @Async 내부에서 FK 위반이 발생해
        // catch 블록에서 조용히 실패하고, 로그가 저장되지 않아 테스트가 깨진다.
        // seed 사용자 ID(9001)를 사용하도록 변경한다.
        Long validUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true LIMIT 1",
                Long.class);

        searchService.logSearch(validUserId, keywordForCleanup, 5, "127.0.0.1", "JUnit-Integration");

        // @Async로 변경된 logSearch가 별도 스레드에서 완료되어 DB 상태가 기대값이 될 때까지 폴링
        await()
                .atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    int current = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                            Integer.class, keywordForCleanup);

                    assertThat(current)
                            .withFailMessage("검색 로그 반영 대기 타임아웃 - keyword=%s, before=%s, expected=%s, current=%s",
                                    keywordForCleanup, before, before + 1, current)
                            .isEqualTo(before + 1);
                });
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
