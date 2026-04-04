package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.repository.CategoryRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceUnitTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    @Test
    @DisplayName("getAllDescendantIds - CTE 단일 쿼리로 하위 카테고리 ID를 모두 조회")
    void getAllDescendantIds_delegatesToCteQuery() {
        when(categoryRepository.findAllDescendantIds(1)).thenReturn(List.of(1, 2, 3));

        List<Integer> ids = categoryService.getAllDescendantIds(1);

        assertThat(ids)
                .as("CTE 쿼리 결과가 그대로 반환되어야 함")
                .containsExactly(1, 2, 3);
        verify(categoryRepository).findAllDescendantIds(1);
    }

    @Test
    @DisplayName("getAllDescendantIds - 하위 카테고리 없으면 루트만 반환")
    void getAllDescendantIds_leafCategory_returnsOnlyRoot() {
        when(categoryRepository.findAllDescendantIds(5)).thenReturn(List.of(5));

        List<Integer> ids = categoryService.getAllDescendantIds(5);

        assertThat(ids)
                .as("하위 카테고리가 없으면 루트 ID만 반환")
                .containsExactly(5);
    }

    @Test
    @DisplayName("getBreadcrumb - 부모 순환 참조면 BusinessException")
    void getBreadcrumb_cycleDetected_throwsBusinessException() {
        Category category1 = mock(Category.class);
        Category category2 = mock(Category.class);

        when(categoryRepository.findById(1)).thenReturn(Optional.of(category1));
        when(category1.getCategoryId()).thenReturn(1);
        when(category1.getParentCategory()).thenReturn(category2);
        when(category2.getCategoryId()).thenReturn(2);
        when(category2.getParentCategory()).thenReturn(category1);

        assertThatThrownBy(() -> categoryService.getBreadcrumb(1))
                .as("부모 경로에 순환이 있으면 비즈니스 예외가 발생해야 함")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("순환");
    }

    @Test
    @DisplayName("findById - 존재하지 않는 카테고리면 ResourceNotFoundException")
    void findById_notFound_throwsException() {
        when(categoryRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findById(999))
                .as("없는 카테고리 조회 시 예외가 발생해야 함")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
