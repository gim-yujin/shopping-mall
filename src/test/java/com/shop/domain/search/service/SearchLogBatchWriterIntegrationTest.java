package com.shop.domain.search.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 19] SearchLogBatchWriter JDBC 배치 INSERT 통합 테스트.
 *
 * <h3>검증 범위</h3>
 * <ul>
 *   <li>다건 배치 INSERT가 DB에 정확히 저장되는지 검증</li>
 *   <li>nullable 컬럼(userId, ipAddress, userAgent) 처리</li>
 *   <li>PostgreSQL inet 타입 캐스트(?::inet) 정상 동작</li>
 *   <li>빈 리스트 전달 시 0 반환</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class SearchLogBatchWriterIntegrationTest {

    @Autowired
    private SearchLogBatchWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CLEANUP_KEYWORD_PREFIX = "BATCH_WRITER_TEST_";

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM search_logs WHERE search_keyword LIKE ?",
                CLEANUP_KEYWORD_PREFIX + "%");
    }

    @Test
    @DisplayName("다건 배치 INSERT — DB에 정확히 저장")
    void writeBatch_insertsMultipleRows() {
        String keyword1 = CLEANUP_KEYWORD_PREFIX + System.currentTimeMillis() + "_1";
        String keyword2 = CLEANUP_KEYWORD_PREFIX + System.currentTimeMillis() + "_2";

        // test-seed.sql에서 생성된 유효 사용자 ID 조회
        Long validUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true LIMIT 1", Long.class);

        List<SearchLogEntry> entries = List.of(
                new SearchLogEntry(validUserId, keyword1, 10, "127.0.0.1", "JUnit", LocalDateTime.now()),
                new SearchLogEntry(validUserId, keyword2, 20, "192.168.1.1", "TestAgent", LocalDateTime.now())
        );

        int saved = writer.writeBatch(entries);

        assertThat(saved).isEqualTo(2);

        // [Phase 19] host() 함수로 inet 타입에서 순수 IP만 추출.
        // PostgreSQL inet 타입은 ::text 캐스트 시 CIDR 마스크를 포함(예: "127.0.0.1/32")하므로
        // host() 함수를 사용하여 마스크 없는 IP 문자열을 반환받는다.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT search_keyword, result_count, host(ip_address) as ip_address, user_agent " +
                        "FROM search_logs WHERE search_keyword IN (?, ?) ORDER BY search_keyword",
                keyword1, keyword2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("search_keyword")).isEqualTo(keyword1);
        assertThat(rows.get(0).get("result_count")).isEqualTo(10);
        assertThat(rows.get(0).get("ip_address")).isEqualTo("127.0.0.1");
        assertThat(rows.get(1).get("search_keyword")).isEqualTo(keyword2);
        assertThat(rows.get(1).get("result_count")).isEqualTo(20);
    }

    @Test
    @DisplayName("nullable 컬럼 — userId, ipAddress, userAgent가 null이어도 저장 성공")
    void writeBatch_handlesNullableFields() {
        String keyword = CLEANUP_KEYWORD_PREFIX + "nullable_" + System.currentTimeMillis();

        List<SearchLogEntry> entries = List.of(
                new SearchLogEntry(null, keyword, 0, null, null, LocalDateTime.now())
        );

        int saved = writer.writeBatch(entries);

        assertThat(saved).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, ip_address, user_agent FROM search_logs WHERE search_keyword = ?",
                keyword);

        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("ip_address")).isNull();
        assertThat(row.get("user_agent")).isNull();
    }

    @Test
    @DisplayName("빈 리스트 — 0 반환, DB 호출 없음")
    void writeBatch_emptyList_returnsZero() {
        int saved = writer.writeBatch(List.of());
        assertThat(saved).isZero();
    }
}
