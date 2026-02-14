package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CategoryService 추가 단위 테스트 — getSubCategories
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceUnitTestSupplementary {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    @Test
    @DisplayName("getSubCategories — parentId로 하위 카테고리 조회 위임")
    void getSubCategories_delegatesToRepository() {
        Category child1 = mock(Category.class);
        Category child2 = mock(Category.class);
        when(categoryRepository.findByParentId(1)).thenReturn(List.of(child1, child2));

        List<Category> result = categoryService.getSubCategories(1);

        assertThat(result).hasSize(2);
        verify(categoryRepository).findByParentId(1);
    }

    @Test
    @DisplayName("getSubCategories — 하위 카테고리 없으면 빈 리스트")
    void getSubCategories_noChildren_returnsEmptyList() {
        when(categoryRepository.findByParentId(999)).thenReturn(List.of());

        List<Category> result = categoryService.getSubCategories(999);

        assertThat(result).isEmpty();
    }
}
