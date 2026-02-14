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

        searchService.logSearch(11L, keywordForCleanup, 5, "127.0.0.1", "JUnit-Integration");

        int after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                Integer.class, keywordForCleanup);

        assertThat(after)
                .as("검색 로그가 1건 추가되어야 함")
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
