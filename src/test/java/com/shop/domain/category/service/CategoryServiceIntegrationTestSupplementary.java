package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * CategoryService 추가 통합 테스트 — getSubCategories
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CategoryServiceIntegrationTestSupplementary {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("getSubCategories — 하위 카테고리 있는 부모 조회")
    void getSubCategories_withChildren_returnsChildList() {
        // 하위 카테고리가 있는 부모 ID 찾기
        Integer parentId = jdbcTemplate.queryForObject(
                """
                SELECT parent_category_id FROM categories
                WHERE parent_category_id IS NOT NULL AND is_active = true
                GROUP BY parent_category_id
                ORDER BY COUNT(*) DESC LIMIT 1
                """,
                Integer.class);

        List<Category> children = categoryService.getSubCategories(parentId);

        assertThat(children).isNotEmpty();
        System.out.println("  [PASS] getSubCategories(parentId=" + parentId + "): " + children.size() + "개");
    }

    @Test
    @DisplayName("getSubCategories — 리프 카테고리 → 빈 리스트")
    void getSubCategories_leafCategory_returnsEmptyList() {
        // 하위가 없는 카테고리 (leaf) 찾기
        Integer leafId = jdbcTemplate.queryForObject(
                """
                SELECT c.category_id FROM categories c
                WHERE c.is_active = true
                  AND NOT EXISTS (SELECT 1 FROM categories child WHERE child.parent_category_id = c.category_id)
                LIMIT 1
                """,
                Integer.class);

        List<Category> children = categoryService.getSubCategories(leafId);

        assertThat(children).isEmpty();
        System.out.println("  [PASS] getSubCategories(leafId=" + leafId + "): 빈 리스트");
    }
}
