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
        List<Category> topCategories = categoryService.getTopLevelCategories();

        assertThat(topCategories)
                .as("최상위 카테고리 목록은 null이 아니어야 함")
                .isNotNull();
        assertThat(topCategories)
                .as("반환 카테고리는 모두 level=1 이어야 함")
                .allMatch(c -> c.getLevel() == 1);
    }

    @Test
    @DisplayName("getBreadcrumb - 선택 카테고리까지 경로 반환")
    void getBreadcrumb_returnsPathToCategory() {
        Integer targetCategoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM categories WHERE is_active = true ORDER BY level DESC, category_id LIMIT 1",
                Integer.class);

        List<Category> breadcrumb = categoryService.getBreadcrumb(targetCategoryId);

        assertThat(breadcrumb)
                .as("브레드크럼 마지막 항목은 대상 카테고리여야 함")
                .isNotEmpty();
        assertThat(breadcrumb.get(breadcrumb.size() - 1).getCategoryId())
                .isEqualTo(targetCategoryId);
    }
}
