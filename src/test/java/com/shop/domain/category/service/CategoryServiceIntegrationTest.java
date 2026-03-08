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

@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CategoryServiceIntegrationTest {


    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("getTopLevelCategories - 최상위 활성 카테고리 조회")
    void getTopLevelCategories_returnsLevelOneActive() {
        Integer rootId = insertCategory("fixture_root_category", null, 1, 10);

        List<Category> topCategories = categoryService.getTopLevelCategories();

        assertThat(topCategories)
                .as("최상위 카테고리 목록은 null이 아니어야 함")
                .isNotNull();
        assertThat(topCategories)
                .as("반환 카테고리는 모두 level=1 이어야 함")
                .allMatch(c -> c.getLevel() == 1);
        assertThat(topCategories)
                .as("fixture로 생성한 최상위 카테고리가 포함되어야 함")
                .anyMatch(c -> c.getCategoryId().equals(rootId));
    }

    @Test
    @DisplayName("getBreadcrumb - 선택 카테고리까지 경로 반환")
    void getBreadcrumb_returnsPathToCategory() {
        Integer rootId = insertCategory("fixture_root_category", null, 1, 20);
        Integer childId = insertCategory("fixture_child_category", rootId, 2, 20);

        List<Category> breadcrumb = categoryService.getBreadcrumb(childId);

        assertThat(breadcrumb)
                .as("브레드크럼 마지막 항목은 대상 카테고리여야 함")
                .isNotEmpty();
        assertThat(breadcrumb.get(0).getCategoryId()).isEqualTo(rootId);
        assertThat(breadcrumb.get(breadcrumb.size() - 1).getCategoryId())
                .isEqualTo(childId);
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
