package com.shop.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class TestDataFactory {

    private final JdbcTemplate jdbcTemplate;

    public TestDataFactory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FixtureContext newContext() {
        return new FixtureContext(jdbcTemplate);
    }

    public static final class FixtureContext {
        private final JdbcTemplate jdbcTemplate;
        private final List<Long> createdProductIds = new ArrayList<>();
        private final List<Long> createdUserIds = new ArrayList<>();
        private final List<Integer> createdCategoryIds = new ArrayList<>();

        private FixtureContext(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        public Long createActiveUser() {
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String username = "test_user_" + token;
            String email = username + "@example.com";

            Long userId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO users (username, email, password_hash, name, role, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'ROLE_USER', true, NOW(), NOW())
                    RETURNING user_id
                    """,
                    Long.class,
                    username,
                    email,
                    "test-password-hash",
                    "테스트 사용자 " + token);

            createdUserIds.add(userId);
            return userId;
        }

        public Long createActiveProduct(int stockQuantity) {
            Integer categoryId = findAnyActiveCategoryId();
            if (categoryId == null) {
                categoryId = createCategory();
            }

            String productName = "테스트 상품_" + UUID.randomUUID();
            Long productId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO products (product_name, category_id, description, price, original_price,
                                          stock_quantity, sales_count, view_count, rating_avg, review_count,
                                          is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, true, NOW(), NOW())
                    RETURNING product_id
                    """,
                    Long.class,
                    productName,
                    categoryId,
                    "동시성 테스트용 fixture 상품",
                    BigDecimal.valueOf(10000),
                    BigDecimal.valueOf(12000),
                    stockQuantity);

            createdProductIds.add(productId);
            return productId;
        }

        public List<Long> createActiveProducts(int count, int stockQuantity) {
            List<Long> productIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                productIds.add(createActiveProduct(stockQuantity));
            }
            return productIds;
        }

        private Integer findAnyActiveCategoryId() {
            List<Integer> ids = jdbcTemplate.queryForList(
                    "SELECT category_id FROM categories WHERE is_active = true ORDER BY category_id LIMIT 1",
                    Integer.class);
            return ids.isEmpty() ? null : ids.get(0);
        }

        private Integer createCategory() {
            String categoryName = "테스트 카테고리_" + UUID.randomUUID();
            Integer categoryId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO categories (category_name, level, display_order, is_active, created_at)
                    VALUES (?, 1, 0, true, NOW())
                    RETURNING category_id
                    """,
                    Integer.class,
                    categoryName);
            createdCategoryIds.add(categoryId);
            return categoryId;
        }

        public void cleanup() {
            for (Long productId : createdProductIds) {
                jdbcTemplate.update("DELETE FROM carts WHERE product_id = ?", productId);
                jdbcTemplate.update("DELETE FROM product_inventory_history WHERE product_id = ?", productId);
                jdbcTemplate.update("DELETE FROM wishlists WHERE product_id = ?", productId);
                jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", productId);
            }

            for (Long userId : createdUserIds) {
                jdbcTemplate.update("DELETE FROM product_inventory_history WHERE created_by = ?", userId);
                jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
            }

            for (Integer categoryId : createdCategoryIds) {
                jdbcTemplate.update("DELETE FROM categories WHERE category_id = ?", categoryId);
            }
        }
    }
}
