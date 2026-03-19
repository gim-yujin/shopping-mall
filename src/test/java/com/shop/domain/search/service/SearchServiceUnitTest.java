package com.shop.domain.search.service;

import com.shop.domain.search.repository.SearchLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceUnitTest {

    @Mock
    private SearchLogRepository searchLogRepository;

    @Mock
    private SearchLogBatchAccumulator batchAccumulator;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        // [Phase 19] SearchService 생성자에 배치 누적기 추가.
        // 기존: SearchService(SearchLogRepository)
        // 변경: SearchService(SearchLogRepository, SearchLogBatchAccumulator)
        searchService = new SearchService(searchLogRepository, batchAccumulator);
    }

    @Test
    @DisplayName("getPopularKeywords - 집계 결과의 키워드 컬럼만 매핑")
    void getPopularKeywords_mapsFirstColumnOnly() {
        when(searchLogRepository.findPopularKeywords()).thenReturn(List.of(
                new Object[]{"노트북", 12L},
                new Object[]{"키보드", 5L}
        ));

        List<String> keywords = searchService.getPopularKeywords();

        assertThat(keywords)
                .as("집계 row의 첫 번째 컬럼(키워드)만 문자열 목록으로 반환해야 함")
                .containsExactly("노트북", "키보드");
    }

    // [Phase 19] 아래 테스트들은 개별 save() 대신 배치 누적기 위임을 검증한다.
    // 기존: verify(searchLogRepository).save(captor.capture()) → SearchLog 엔티티 캡처
    // 변경: verify(batchAccumulator).add(captor.capture()) → SearchLogEntry 값 객체 캡처

    @Test
    @DisplayName("logSearch - 배치 누적기에 검색 로그 엔트리를 추가")
    void logSearch_addsEntryToAccumulator() {
        when(batchAccumulator.add(org.mockito.ArgumentMatchers.any(SearchLogEntry.class)))
                .thenReturn(true);

        searchService.logSearch(11L, "모니터", 7, "127.0.0.1", "JUnit");

        ArgumentCaptor<SearchLogEntry> captor =
                ArgumentCaptor.forClass(SearchLogEntry.class);
        verify(batchAccumulator).add(captor.capture());

        SearchLogEntry captured = captor.getValue();
        assertThat(captured.keyword())
                .as("입력한 키워드가 엔트리에 저장되어야 함")
                .isEqualTo("모니터");
        assertThat(captured.userId()).isEqualTo(11L);
        assertThat(captured.resultCount()).isEqualTo(7);
        assertThat(captured.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(captured.userAgent()).isEqualTo("JUnit");
        assertThat(captured.searchedAt()).isNotNull();
    }

    @Test
    @DisplayName("logSearch - 비로그인 사용자도 로그 추가 가능 (userId=null)")
    void logSearch_handlesNullUserId() {
        when(batchAccumulator.add(org.mockito.ArgumentMatchers.any(SearchLogEntry.class)))
                .thenReturn(true);

        searchService.logSearch(null, "키보드", 3, "10.0.0.1", "JUnit");

        ArgumentCaptor<SearchLogEntry> captor =
                ArgumentCaptor.forClass(SearchLogEntry.class);
        verify(batchAccumulator).add(captor.capture());

        assertThat(captor.getValue().userId()).isNull();
    }

    @Test
    @DisplayName("logSearch - 버퍼 오버플로우 시 예외 없이 경고 로그만 출력")
    void logSearch_doesNotThrowWhenBufferFull() {
        // [Phase 19] 버퍼가 가득 차면 add()가 false를 반환하지만 예외는 발생하지 않는다.
        // 기존 asyncExecutor DiscardPolicy와 동일한 정책.
        when(batchAccumulator.add(org.mockito.ArgumentMatchers.any(SearchLogEntry.class)))
                .thenReturn(false);

        assertThatCode(() -> searchService.logSearch(11L, "모니터", 7, "127.0.0.1", "JUnit"))
                .as("버퍼 오버플로우는 삼켜지고 예외가 전파되지 않아야 함")
                .doesNotThrowAnyException();
    }
}
