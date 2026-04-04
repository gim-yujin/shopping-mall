package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.repository.CategoryRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Service
@Transactional(readOnly = true)
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    // [Phase 10] sync = true: 스탬피드 방지 — 상세 설명은 ProductService.findByIdCached() 참조
    @Cacheable(value = "topCategories", key = "'all'", sync = true)
    public List<Category> getTopLevelCategories() {
        return categoryRepository.findTopLevelCategories();
    }

    /**
     * 관리자용 — 전체 활성 카테고리 목록 (레벨·정렬순).
     */
    public List<Category> getAllActiveCategories() {
        return categoryRepository.findAllActiveOrderByLevelAndDisplayOrder();
    }

    @Cacheable(value = "categoryById", key = "#categoryId", sync = true)
    public Category findById(Integer categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("카테고리", categoryId));
    }

    @Cacheable(value = "subCategories", key = "#parentId", sync = true)
    public List<Category> getSubCategories(Integer parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    /**
     * 카테고리 ID와 모든 하위 카테고리 ID를 반환한다.
     * 카테고리 트리 구조는 거의 변하지 않으므로 캐싱에 적합하다.
     *
     * <p>[Phase 25] 재귀 N+1 → WITH RECURSIVE CTE 단일 쿼리로 대체.
     * 기존: 트리 노드마다 findByParentId() 호출 → O(N) 쿼리.
     * 이후: 단일 CTE 쿼리 → 1 쿼리. 캐시 히트 시 DB 접근 0회.</p>
     */
    @Cacheable(value = "categoryDescendants", key = "#categoryId", sync = true)
    public List<Integer> getAllDescendantIds(Integer categoryId) {
        return categoryRepository.findAllDescendantIds(categoryId);
    }

    /**
     * 브레드크럼 경로를 반환한다 (루트 → 현재 카테고리).
     * 이전: 매 요청마다 부모 순회 N회 쿼리
     * 이후: 캐시 히트 시 DB 접근 0회
     */
    @Cacheable(value = "categoryBreadcrumb", key = "#categoryId", sync = true)
    public List<Category> getBreadcrumb(Integer categoryId) {
        List<Category> breadcrumb = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Category current = findById(categoryId);
        while (current != null) {
            Integer currentId = current.getCategoryId();
            if (!visited.add(currentId)) {
                log.warn("카테고리 브레드크럼 순환 감지: categoryId={}, loopId={}", categoryId, currentId);
                throw new BusinessException("CATEGORY_CYCLE", "카테고리 계층 구조에 순환이 감지되었습니다.");
            }
            breadcrumb.add(0, current);
            current = current.getParentCategory();
        }
        return breadcrumb;
    }
}
