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
    @DisplayName("getAllDescendantIds - 재귀적으로 하위 카테고리 ID를 모두 수집")
    void getAllDescendantIds_collectsRecursively() {
        Category child = mock(Category.class);
        Category grandChild = mock(Category.class);

        when(child.getCategoryId()).thenReturn(2);
        when(grandChild.getCategoryId()).thenReturn(3);

        when(categoryRepository.findByParentId(1)).thenReturn(List.of(child));
        when(categoryRepository.findByParentId(2)).thenReturn(List.of(grandChild));
        when(categoryRepository.findByParentId(3)).thenReturn(List.of());

        List<Integer> ids = categoryService.getAllDescendantIds(1);

        assertThat(ids)
                .as("루트 + 모든 하위 ID가 포함되어야 함")
                .containsExactly(1, 2, 3);
    }


    @Test
    @DisplayName("getAllDescendantIds - 순환 참조가 있어도 중복/무한 재귀 없이 종료")
    void getAllDescendantIds_cycleDetected_stopsSafely() {
        Category child = mock(Category.class);
        Category rootAsChild = mock(Category.class);

        when(child.getCategoryId()).thenReturn(2);
        when(rootAsChild.getCategoryId()).thenReturn(1);

        when(categoryRepository.findByParentId(1)).thenReturn(List.of(child));
        when(categoryRepository.findByParentId(2)).thenReturn(List.of(rootAsChild));

        List<Integer> ids = categoryService.getAllDescendantIds(1);

        assertThat(ids)
                .as("순환이 있어도 각 카테고리 ID는 한 번만 수집되어야 함")
                .containsExactly(1, 2);
        verify(categoryRepository).findByParentId(1);
        verify(categoryRepository).findByParentId(2);
        verifyNoMoreInteractions(categoryRepository);
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
