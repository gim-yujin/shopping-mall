package com.shop.domain.search.controller;

import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    // [Phase 18] ProductService → ProductQueryService: CQRS 읽기 경로 분리에 따라 읽기 모의 객체 변경
    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private SearchService searchService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private BackpressureDetector backpressureDetector;

    @Mock
    private HttpServletRequest request;

    @Test
    @DisplayName("search - 200자 초과 검색어는 잘라서 검색하고 안내 메시지를 제공")
    void search_truncatesKeywordOver200AndAddsMessage() {
        SearchController controller = new SearchController(productQueryService, searchService, clientIpResolver, backpressureDetector);
        Model model = new ConcurrentModel();
        String longKeyword = "가".repeat(201);
        // [Phase 18] Page<Product> → Page<ProductListReadModel>: CQRS 읽기 모델 전환
        Page<ProductListReadModel> results = new PageImpl<>(List.of());

        when(productQueryService.search(any(String.class), any(PageRequest.class))).thenReturn(results);
        when(clientIpResolver.resolveClientIp(request)).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        String viewName = controller.search(longKeyword, 0, 20, request, model);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(productQueryService).search(keywordCaptor.capture(), eq(PageRequest.of(0, 20)));

        assertThat(viewName).isEqualTo("product/search");
        assertThat(keywordCaptor.getValue()).hasSize(200);
        assertThat(model.getAttribute("keyword")).isEqualTo(keywordCaptor.getValue());
        assertThat(model.getAttribute("keywordValidationMessage"))
                .isEqualTo("검색어는 최대 200자까지 입력할 수 있어 앞부분만 검색했어요.");
    }

    @Test
    @DisplayName("search - 프록시 없는 환경에서는 remoteAddr 기반 IP가 검색 로그에 기록된다")
    void search_logsResolvedClientIp_withoutProxy() {
        SearchController controller = new SearchController(productQueryService, searchService, clientIpResolver, backpressureDetector);
        Model model = new ConcurrentModel();
        // [Phase 18] Page<Product> → Page<ProductListReadModel>: CQRS 읽기 모델 전환
        Page<ProductListReadModel> results = new PageImpl<>(List.of(), PageRequest.of(0, 20), 3);

        when(productQueryService.search("phone", PageRequest.of(0, 20))).thenReturn(results);
        when(clientIpResolver.resolveClientIp(request)).thenReturn("198.51.100.20");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        controller.search("phone", 0, 20, request, model);

        verify(searchService).logSearch(any(), eq("phone"), eq(3), eq("198.51.100.20"), eq("JUnit"));
    }

    @Test
    @DisplayName("search - trusted proxy 환경에서는 X-Forwarded-For 해석 결과 IP가 검색 로그에 기록된다")
    void search_logsResolvedClientIp_withTrustedProxy() {
        SearchController controller = new SearchController(productQueryService, searchService, clientIpResolver, backpressureDetector);
        Model model = new ConcurrentModel();
        // [Phase 18] Page<Product> → Page<ProductListReadModel>: CQRS 읽기 모델 전환
        Page<ProductListReadModel> results = new PageImpl<>(List.of(), PageRequest.of(0, 20), 7);

        when(productQueryService.search("laptop", PageRequest.of(0, 20))).thenReturn(results);
        when(clientIpResolver.resolveClientIp(request)).thenReturn("203.0.113.5");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        controller.search("laptop", 0, 20, request, model);

        verify(searchService).logSearch(any(), eq("laptop"), eq(7), eq("203.0.113.5"), eq("JUnit"));
        verify(request, never()).getRemoteAddr();
    }
}
