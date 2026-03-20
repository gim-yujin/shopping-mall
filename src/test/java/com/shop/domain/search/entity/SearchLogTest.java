package com.shop.domain.search.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchLog 엔티티 단위 테스트.
 *
 * <p>검색 로그 엔티티의 생성자, 키워드 정규화 로직, getter를 검증한다.
 * JPA 영속성은 통합 테스트(SearchServiceIntegrationTest)에서 검증하므로,
 * 여기서는 순수 Java 로직만 테스트한다.</p>
 */
class SearchLogTest {

    // ── 생성자 ──

    @Nested
    @DisplayName("생성자 — 정상 생성")
    class ConstructorTest {

        @Test
        @DisplayName("모든 필드가 정상적으로 설정된다")
        void allFieldsSetCorrectly() {
            SearchLog log = new SearchLog(1L, "노트북", 42, "127.0.0.1", "Mozilla/5.0");

            assertThat(log.getUserId()).isEqualTo(1L);
            assertThat(log.getSearchKeyword()).isEqualTo("노트북");
            assertThat(log.getResultCount()).isEqualTo(42);
            // searchedAt은 생성 시점에 설정되므로 null이 아닌지만 확인
            assertThat(log.getSearchedAt()).isNotNull();
        }

        @Test
        @DisplayName("userId가 null이어도 정상 생성된다 — 비로그인 사용자 검색")
        void nullUserId_allowedForAnonymousSearch() {
            SearchLog log = new SearchLog(null, "키보드", 10, "192.168.1.1", "Chrome");

            assertThat(log.getUserId()).isNull();
            assertThat(log.getSearchKeyword()).isEqualTo("키보드");
        }
    }

    // ── 키워드 정규화 ──

    @Nested
    @DisplayName("normalizeSearchKeyword — 키워드 정규화")
    class NormalizeKeywordTest {

        @Test
        @DisplayName("null 키워드는 빈 문자열로 변환된다")
        void nullKeyword_convertedToEmpty() {
            // normalizeSearchKeyword(null) → "" 로 변환하여 NOT NULL 제약 충족
            SearchLog log = new SearchLog(1L, null, 0, "127.0.0.1", null);

            assertThat(log.getSearchKeyword()).isEmpty();
        }

        @Test
        @DisplayName("앞뒤 공백이 제거된다")
        void leadingTrailingSpaces_trimmed() {
            SearchLog log = new SearchLog(1L, "  노트북  ", 5, "127.0.0.1", null);

            assertThat(log.getSearchKeyword()).isEqualTo("노트북");
        }

        @Test
        @DisplayName("200자를 초과하는 키워드는 200자로 잘린다 — DB 컬럼 길이 제한 보호")
        void longKeyword_truncatedTo200() {
            // MAX_SEARCH_KEYWORD_LENGTH = 200
            String longKeyword = "가".repeat(250);

            SearchLog log = new SearchLog(1L, longKeyword, 0, "127.0.0.1", null);

            assertThat(log.getSearchKeyword()).hasSize(200);
        }

        @Test
        @DisplayName("정확히 200자인 키워드는 잘리지 않는다")
        void exactlyMaxLength_notTruncated() {
            String exact200 = "a".repeat(200);

            SearchLog log = new SearchLog(1L, exact200, 0, "127.0.0.1", null);

            assertThat(log.getSearchKeyword()).hasSize(200);
            assertThat(log.getSearchKeyword()).isEqualTo(exact200);
        }
    }

    // ── Getter ──

    @Nested
    @DisplayName("Getter")
    class GetterTest {

        @Test
        @DisplayName("logId는 영속화 전 null이다")
        void logId_nullBeforePersistence() {
            SearchLog log = new SearchLog(1L, "test", 0, "127.0.0.1", null);

            // @GeneratedValue(IDENTITY)이므로 DB 저장 전에는 null
            assertThat(log.getLogId()).isNull();
        }

        @Test
        @DisplayName("clickedProductId는 초기값 null이다")
        void clickedProductId_initiallyNull() {
            SearchLog log = new SearchLog(1L, "test", 0, "127.0.0.1", null);

            // clickedProductId는 생성자에서 설정하지 않으므로 null
            assertThat(log.getClickedProductId()).isNull();
        }
    }
}
