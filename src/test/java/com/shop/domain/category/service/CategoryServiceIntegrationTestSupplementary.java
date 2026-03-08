package com.shop.domain.category.service;

import com.shop.domain.category.entity.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CategoryService 추가 통합 테스트 — getSubCategories
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CategoryServiceIntegrationTestSupplementary {

    private static final String FIXTURE_PARENT_NAME = "fixture_sub_parent";
    private static final String FIXTURE_CHILD_NAME = "fixture_sub_child";
    private static final String FIXTURE_LEAF_NAME = "fixture_leaf";

    CategoryServiceIntegrationTestSupplementary() {
        // 기본 생성자 (PMD AtLeastOneConstructor 대응)
    }

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("getSubCategories — 하위 카테고리 있는 부모 조회")
    void getSubCategories_withChildren_returnsChildList() {
        Integer parentId = insertCategory(FIXTURE_PARENT_NAME, null, 1, 30);
        Integer childId = insertCategory(FIXTURE_CHILD_NAME, parentId, 2, 30);

        List<Category> children = categoryService.getSubCategories(parentId);

        assertThat(children).isNotEmpty();
        assertThat(children).extracting(Category::getCategoryId).contains(childId);
        System.out.println("  [PASS] getSubCategories(parentId=" + parentId + "): " + children.size() + "개");
    }

    @Test
    @DisplayName("getSubCategories — 리프 카테고리 → 빈 리스트")
    void getSubCategories_leafCategory_returnsEmptyList() {
        Integer leafId = insertCategory(FIXTURE_LEAF_NAME, null, 1, 40);

        List<Category> children = categoryService.getSubCategories(leafId);

        assertThat(children).isEmpty();
        System.out.println("  [PASS] getSubCategories(leafId=" + leafId + "): 빈 리스트");
    }

    private Integer insertCategory(String baseName, Integer parentId, int level, int displayOrder) {
        String uniqueName = baseName + "_" + System.nanoTime();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO categories (category_name, parent_category_id, level, display_order, is_active, created_at)
                VALUES (?, ?, ?, ?, true, NOW())
                RETURNING category_id
                """,
                Integer.class,
                uniqueName,
                parentId,
                level,
                displayOrder
        );
    }
}
