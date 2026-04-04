package com.shop.domain.category.repository;

import com.shop.domain.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Integer> {

    @Query("SELECT c FROM Category c WHERE c.level = 1 AND c.isActive = true ORDER BY c.displayOrder")
    List<Category> findTopLevelCategories();

    @Query("SELECT c FROM Category c WHERE c.parentCategory.categoryId = :parentId AND c.isActive = true ORDER BY c.displayOrder")
    List<Category> findByParentId(Integer parentId);

    List<Category> findByLevelAndIsActiveTrueOrderByDisplayOrder(int level);

    @Query("SELECT c FROM Category c WHERE c.isActive = true ORDER BY c.level, c.displayOrder")
    List<Category> findAllActiveOrderByLevelAndDisplayOrder();

    /**
     * [Phase 25] 카테고리 하위 트리를 단일 WITH RECURSIVE CTE로 조회한다.
     *
     * <p><b>문제:</b> 기존 collectChildIds()는 트리 노드마다 findByParentId()를
     * 재귀 호출하여 O(N) 쿼리를 발생시켰다 (N = 하위 노드 수).
     * 캐시(categoryDescendants)로 완화되지만 cold start 시 부담이 컸다.</p>
     *
     * <p><b>해결:</b> PostgreSQL WITH RECURSIVE CTE로 단일 쿼리에서
     * 전체 하위 트리를 탐색한다. depth &lt; 10 제한으로 무한 재귀를 방지한다.</p>
     *
     * @param categoryId 루트 카테고리 ID
     * @return 루트를 포함한 모든 하위 카테고리 ID 목록
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT category_id, 0 AS depth
                FROM categories WHERE category_id = :categoryId
                UNION ALL
                SELECT c.category_id, d.depth + 1
                FROM categories c
                INNER JOIN descendants d ON c.parent_category_id = d.category_id
                WHERE c.is_active = true AND d.depth < 10
            )
            SELECT category_id FROM descendants
            """, nativeQuery = true)
    List<Integer> findAllDescendantIds(@Param("categoryId") Integer categoryId);
}
