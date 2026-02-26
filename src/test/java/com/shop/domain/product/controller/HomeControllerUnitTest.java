package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeControllerUnitTest {

    @Mock
    private ProductService productService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SearchService searchService;

    @InjectMocks
    private HomeController homeController;

    @Test
    @DisplayName("홈 화면은 동일한 pageable(0,8) 규격으로 홈 상품 섹션을 조회한다")
    void home_usesExpectedHomePageableSpec() {
        PageRequest expectedPageable = PageRequest.of(0, 8);
        when(categoryService.getTopLevelCategories()).thenReturn(List.of());
        when(productService.getBestSellers(eq(expectedPageable))).thenReturn(new PageImpl<>(List.of()));
        when(productService.getNewArrivals(eq(expectedPageable))).thenReturn(new PageImpl<>(List.of()));
        when(productService.getDeals(eq(expectedPageable))).thenReturn(new PageImpl<>(List.of()));
        when(searchService.getPopularKeywords()).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String viewName = homeController.home(model);

        verify(productService).getBestSellers(eq(expectedPageable));
        verify(productService).getNewArrivals(eq(expectedPageable));
        verify(productService).getDeals(eq(expectedPageable));
        assertThat(viewName).isEqualTo("home");
    }
}
