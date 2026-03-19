package com.shop.global.cache;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * [Phase 16] CacheWarmer 단위 테스트.
 *
 * <p>ApplicationReadyEvent 시 핵심 캐시 5종이 사전 로딩되는지,
 * 개별 실패가 전체 워밍을 중단하지 않는지 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CacheWarmerTest {

    @Mock
    private ProductQueryService productService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SearchService searchService;

    @InjectMocks
    private CacheWarmer cacheWarmer;

    @Test
    @DisplayName("애플리케이션 시작 시 5개 핵심 캐시를 사전 로딩한다")
    void warmsAllCriticalCaches() {
        Page<?> emptyPage = new PageImpl<>(Collections.emptyList());
        when(productService.getBestSellers(any())).thenReturn((Page) emptyPage);
        when(productService.getNewArrivals(any())).thenReturn((Page) emptyPage);
        when(productService.getDeals(any())).thenReturn((Page) emptyPage);
        when(categoryService.getTopLevelCategories()).thenReturn(Collections.emptyList());
        when(searchService.getPopularKeywords()).thenReturn(Collections.emptyList());

        cacheWarmer.warmUpCaches();

        verify(productService).getBestSellers(any());
        verify(productService).getNewArrivals(any());
        verify(productService).getDeals(any());
        verify(categoryService).getTopLevelCategories();
        verify(searchService).getPopularKeywords();
    }

    @Test
    @DisplayName("하나의 캐시 워밍 실패가 나머지 캐시 워밍을 중단하지 않는다")
    void singleFailureDoesNotBlockOthers() {
        // bestSellers 워밍이 실패해도 나머지 4개는 정상 실행
        when(productService.getBestSellers(any())).thenThrow(new RuntimeException("DB 미연결"));
        Page<?> emptyPage = new PageImpl<>(Collections.emptyList());
        when(productService.getNewArrivals(any())).thenReturn((Page) emptyPage);
        when(productService.getDeals(any())).thenReturn((Page) emptyPage);
        when(categoryService.getTopLevelCategories()).thenReturn(Collections.emptyList());
        when(searchService.getPopularKeywords()).thenReturn(Collections.emptyList());

        cacheWarmer.warmUpCaches();

        // bestSellers 실패 후에도 나머지가 호출되었는지 검증
        verify(productService).getNewArrivals(any());
        verify(productService).getDeals(any());
        verify(categoryService).getTopLevelCategories();
        verify(searchService).getPopularKeywords();
    }

    @Test
    @DisplayName("모든 캐시 워밍이 실패해도 예외가 전파되지 않는다")
    void allFailuresDoNotPropagate() {
        when(productService.getBestSellers(any())).thenThrow(new RuntimeException("실패1"));
        when(productService.getNewArrivals(any())).thenThrow(new RuntimeException("실패2"));
        when(productService.getDeals(any())).thenThrow(new RuntimeException("실패3"));
        when(categoryService.getTopLevelCategories()).thenThrow(new RuntimeException("실패4"));
        when(searchService.getPopularKeywords()).thenThrow(new RuntimeException("실패5"));

        // 예외 없이 정상 완료되어야 한다
        cacheWarmer.warmUpCaches();

        verify(productService).getBestSellers(any());
        verify(searchService).getPopularKeywords();
    }
}
