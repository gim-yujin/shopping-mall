package com.shop.domain.category.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Category 엔티티 단위 테스트.
 *
 * <p>카테고리 엔티티의 getter, 부모-자식 관계, 기본값을 검증한다.
 * 카테고리는 setter 없이 JPA 리플렉션으로 필드를 설정하므로,
 * 테스트에서도 ReflectionTestUtils로 필드를 주입한다.</p>
 */
class CategoryTest {

    @Test
    @DisplayName("모든 getter가 올바른 값을 반환한다")
    void allGetters_returnCorrectValues() {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "categoryId", 1);
        ReflectionTestUtils.setField(category, "categoryName", "전자제품");
        ReflectionTestUtils.setField(category, "level", 1);
        ReflectionTestUtils.setField(category, "displayOrder", 10);
        ReflectionTestUtils.setField(category, "isActive", true);
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(category, "createdAt", now);

        assertThat(category.getCategoryId()).isEqualTo(1);
        assertThat(category.getCategoryName()).isEqualTo("전자제품");
        assertThat(category.getLevel()).isEqualTo(1);
        assertThat(category.getDisplayOrder()).isEqualTo(10);
        assertThat(category.getIsActive()).isTrue();
        assertThat(category.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("부모 카테고리 관계가 올바르게 설정된다")
    void parentCategory_setCorrectly() {
        // 부모: 전자제품 (레벨 1) → 자식: 노트북 (레벨 2)
        Category parent = new Category();
        ReflectionTestUtils.setField(parent, "categoryId", 1);
        ReflectionTestUtils.setField(parent, "categoryName", "전자제품");
        ReflectionTestUtils.setField(parent, "level", 1);

        Category child = new Category();
        ReflectionTestUtils.setField(child, "categoryId", 2);
        ReflectionTestUtils.setField(child, "categoryName", "노트북");
        ReflectionTestUtils.setField(child, "level", 2);
        ReflectionTestUtils.setField(child, "parentCategory", parent);

        assertThat(child.getParentCategory()).isEqualTo(parent);
        assertThat(child.getParentCategory().getCategoryName()).isEqualTo("전자제품");
    }

    @Test
    @DisplayName("자식 카테고리 리스트는 기본값으로 빈 리스트이다")
    void children_defaultEmptyList() {
        Category category = new Category();

        // @OneToMany 컬렉션은 new ArrayList<>()로 초기화되어 있으므로 null이 아닌 빈 리스트
        assertThat(category.getChildren()).isNotNull();
        assertThat(category.getChildren()).isEmpty();
    }

    @Test
    @DisplayName("부모가 없는 최상위 카테고리는 parentCategory가 null이다")
    void rootCategory_parentIsNull() {
        Category root = new Category();
        ReflectionTestUtils.setField(root, "categoryId", 1);
        ReflectionTestUtils.setField(root, "level", 0);

        assertThat(root.getParentCategory()).isNull();
    }
}
