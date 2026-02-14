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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceUnitTest {

    @Mock
    private SearchLogRepository searchLogRepository;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(searchLogRepository);
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

    @Test
    @DisplayName("logSearch - 검색 로그를 저장")
    void logSearch_savesEntity() {
        searchService.logSearch(11L, "모니터", 7, "127.0.0.1", "JUnit");

        ArgumentCaptor<com.shop.domain.search.entity.SearchLog> captor =
                ArgumentCaptor.forClass(com.shop.domain.search.entity.SearchLog.class);
        verify(searchLogRepository).save(captor.capture());

        assertThat(captor.getValue().getSearchKeyword())
                .as("입력한 키워드가 엔티티에 저장되어야 함")
                .isEqualTo("모니터");
    }
}
