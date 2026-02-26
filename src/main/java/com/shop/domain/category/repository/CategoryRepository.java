package com.shop.domain.category.repository;

import com.shop.domain.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Integer> {

    @Query("SELECT c FROM Category c WHERE c.level = 1 AND c.isActive = true ORDER BY c.displayOrder")
    List<Category> findTopLevelCategories();

    @Query("SELECT c FROM Category c WHERE c.parentCategory.categoryId = :parentId AND c.isActive = true ORDER BY c.displayOrder")
    List<Category> findByParentId(Integer parentId);

    List<Category> findByLevelAndIsActiveTrueOrderByDisplayOrder(int level);

    @Query("SELECT c FROM Category c WHERE c.isActive = true ORDER BY c.level, c.displayOrder")
    List<Category> findAllActiveOrderByLevelAndDisplayOrder();
}
