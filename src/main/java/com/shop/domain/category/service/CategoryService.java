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

    @Cacheable(value = "categoryById", key = "#categoryId")
    public Category findById(Integer categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("카테고리", categoryId));
    }

    @Cacheable(value = "subCategories", key = "#parentId")
    public List<Category> getSubCategories(Integer parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    /**
     * 카테고리 ID와 모든 하위 카테고리 ID를 반환한다.
     * 카테고리 트리 구조는 거의 변하지 않으므로 캐싱에 적합하다.
     * 이전: 매 요청마다 재귀 쿼리 N+1회
     * 이후: 캐시 히트 시 DB 접근 0회
     */
    @Cacheable(value = "categoryDescendants", key = "#categoryId")
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

    /**
     * 브레드크럼 경로를 반환한다 (루트 → 현재 카테고리).
     * 이전: 매 요청마다 부모 순회 N회 쿼리
     * 이후: 캐시 히트 시 DB 접근 0회
     */
    @Cacheable(value = "categoryBreadcrumb", key = "#categoryId")
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
