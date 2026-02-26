package com.shop.domain.search.controller;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private ProductService productService;

    @Mock
    private SearchService searchService;

    @Mock
    private HttpServletRequest request;

    @Test
    @DisplayName("search - 200자 초과 검색어는 잘라서 검색하고 안내 메시지를 제공")
    void search_truncatesKeywordOver200AndAddsMessage() {
        SearchController controller = new SearchController(productService, searchService);
        Model model = new ConcurrentModel();
        String longKeyword = "가".repeat(201);
        Page<Product> results = new PageImpl<>(List.of());

        when(productService.search(any(String.class), any(PageRequest.class))).thenReturn(results);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");

        String viewName = controller.search(longKeyword, 0, 20, request, model);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(productService).search(keywordCaptor.capture(), eq(PageRequest.of(0, 20)));

        assertThat(viewName).isEqualTo("product/search");
        assertThat(keywordCaptor.getValue()).hasSize(200);
        assertThat(model.getAttribute("keyword")).isEqualTo(keywordCaptor.getValue());
        assertThat(model.getAttribute("keywordValidationMessage"))
                .isEqualTo("검색어는 최대 200자까지 입력할 수 있어 앞부분만 검색했어요.");
    }
}
