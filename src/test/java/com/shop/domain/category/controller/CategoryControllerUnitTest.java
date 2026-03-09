package com.shop.domain.category.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CategoryController 단위 테스트.
 *
 * <p>GET /categories/{categoryId} 하나의 엔드포인트지만 내부에서
 * CategoryService 4개 메서드 + ProductService 1개 메서드를 호출하며,
 * PagingParams.normalizePage/Size/Sort를 거치므로 파라미터 조합별 검증이 필요하다.</p>
 *
 * <p>커버리지 목표: 11% → 100% (17라인 전체)</p>
 */
@ExtendWith(MockitoExtension.class)
class CategoryControllerUnitTest {

    @Mock
    private CategoryService categoryService;
    @Mock
    private ProductService productService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CategoryController controller = new CategoryController(categoryService, productService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private Category createCategory(Integer id, String name) {
        Category category = mock(Category.class);
        // 컨트롤러는 Category를 모델에 그대로 전달하므로 getter 호출이 보장되지 않는다.
        // Thymeleaf 뷰에서 호출되지만 standaloneSetup에서는 뷰 렌더링이 없으므로
        // lenient 스텁으로 설정하여 UnnecessaryStubbingException을 방지한다.
        lenient().when(category.getCategoryId()).thenReturn(id);
        lenient().when(category.getCategoryName()).thenReturn(name);
        return category;
    }

    /**
     * CategoryService의 모든 메서드에 대한 공통 스텁을 설정한다.
     * categoryProducts 메서드가 5개의 서비스 호출을 순차적으로 수행하므로
     * 누락 시 NullPointerException이 발생한다.
     */
    private void stubCategoryService(Integer categoryId) {
        Category category = createCategory(categoryId, "전자기기");
        when(categoryService.findById(categoryId)).thenReturn(category);
        // 자기 자신 + 하위 카테고리 ID 목록
        when(categoryService.getAllDescendantIds(categoryId)).thenReturn(List.of(categoryId, 20, 21));
        when(categoryService.getSubCategories(categoryId)).thenReturn(Collections.emptyList());
        when(categoryService.getBreadcrumb(categoryId)).thenReturn(List.of(category));
        when(categoryService.getTopLevelCategories()).thenReturn(List.of(category));
    }

    // ── GET /categories/{categoryId} ────────────────────────────

    @Test
    @DisplayName("카테고리 상품 페이지를 렌더링하고 필수 모델 속성을 모두 설정한다")
    void categoryProducts_rendersListView() throws Exception {
        // given: 카테고리 10번, 기본 정렬(best), 기본 페이지(0)
        stubCategoryService(10);
        Page<Product> products = new PageImpl<>(Collections.emptyList());
        when(productService.findByCategoryIdsSorted(anyList(), eq(0), eq(20), eq("best")))
                .thenReturn(products);

        // when & then: 뷰 이름 + 7개 모델 속성 존재 확인
        mockMvc.perform(get("/categories/{categoryId}", 10))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attributeExists(
                        "category", "subCategories", "products",
                        "breadcrumb", "allCategories", "currentSort", "baseUrl"))
                .andExpect(model().attribute("currentSort", "best"))
                .andExpect(model().attribute("baseUrl", "/categories/10"));
    }

    @Test
    @DisplayName("page, size, sort 파라미터가 올바르게 정규화되어 서비스에 전달된다")
    void categoryProducts_withCustomParams_normalizesCorrectly() throws Exception {
        // given: sort=price_asc, page=2, size=40
        stubCategoryService(10);
        Page<Product> products = new PageImpl<>(Collections.emptyList());
        when(productService.findByCategoryIdsSorted(anyList(), eq(2), eq(40), eq("price_asc")))
                .thenReturn(products);

        // when & then: 정규화된 파라미터가 서비스에 전달
        mockMvc.perform(get("/categories/{categoryId}", 10)
                        .param("page", "2")
                        .param("size", "40")
                        .param("sort", "price_asc"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"))
                .andExpect(model().attribute("currentSort", "price_asc"));
    }

    @Test
    @DisplayName("음수 page 파라미터는 0으로 정규화된다")
    void categoryProducts_negativePage_normalizedToZero() throws Exception {
        // given: page=-5 → 0으로 보정
        stubCategoryService(10);
        Page<Product> products = new PageImpl<>(Collections.emptyList());
        // PagingParams.normalizePage(-5) → 0
        when(productService.findByCategoryIdsSorted(anyList(), eq(0), anyInt(), anyString()))
                .thenReturn(products);

        // when & then
        mockMvc.perform(get("/categories/{categoryId}", 10)
                        .param("page", "-5"))
                .andExpect(status().isOk())
                .andExpect(view().name("product/list"));
    }
}
