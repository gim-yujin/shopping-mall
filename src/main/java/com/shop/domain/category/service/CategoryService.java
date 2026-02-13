package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.repository.CategoryRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Cacheable(value = "topCategories", key = "'all'")
    public List<Category> getTopLevelCategories() {
        return categoryRepository.findTopLevelCategories();
    }

    public Category findById(Integer categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("카테고리", categoryId));
    }

    public List<Category> getSubCategories(Integer parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    public List<Integer> getAllDescendantIds(Integer categoryId) {
        List<Integer> ids = new ArrayList<>();
        ids.add(categoryId);
        collectChildIds(categoryId, ids);
        return ids;
    }

    private void collectChildIds(Integer parentId, List<Integer> ids) {
        List<Category> children = categoryRepository.findByParentId(parentId);
        for (Category child : children) {
            ids.add(child.getCategoryId());
            collectChildIds(child.getCategoryId(), ids);
        }
    }

    public List<Category> getBreadcrumb(Integer categoryId) {
        List<Category> breadcrumb = new ArrayList<>();
        Category current = findById(categoryId);
        while (current != null) {
            breadcrumb.add(0, current);
            current = current.getParentCategory();
        }
        return breadcrumb;
    }
}
