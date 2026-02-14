package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.repository.CategoryRepository;
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
    @DisplayName("findById - 존재하지 않는 카테고리면 ResourceNotFoundException")
    void findById_notFound_throwsException() {
        when(categoryRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findById(999))
                .as("없는 카테고리 조회 시 예외가 발생해야 함")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
