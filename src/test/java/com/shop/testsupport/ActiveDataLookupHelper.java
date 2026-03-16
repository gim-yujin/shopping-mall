package com.shop.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ActiveDataLookupHelper {

    private final JdbcTemplate jdbcTemplate;

    public ActiveDataLookupHelper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 테스트 의도: "아무거나 1건"이 필요할 때, PK 오름차순으로 대표 1건을 고정 선택한다.
     */
    public Long findRepresentativeActiveProductId() {
        return jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true ORDER BY product_id LIMIT 1",
                Long.class);
    }

    /**
     * 테스트 의도: "특정 조건의 대표 1건"이 필요할 때, 조건을 만족하는 최소 PK를 선택한다.
     */
    public Long findRepresentativeActiveProductIdWithMinStock(int minStockQuantity) {
        return jdbcTemplate.queryForObject(
                """
                SELECT product_id
                FROM products
                WHERE is_active = true AND stock_quantity >= ?
                ORDER BY product_id
                LIMIT 1
                """,
                Long.class,
                minStockQuantity);
    }

    /**
     * 테스트 의도: "특정 조건의 대표 1건"이 필요할 때, 제외 ID를 뺀 최소 PK를 선택한다.
     */
    public Long findRepresentativeActiveProductIdExcluding(Long excludedProductId, int minStockQuantity) {
        return jdbcTemplate.queryForObject(
                """
                SELECT product_id
                FROM products
                WHERE is_active = true
                  AND stock_quantity >= ?
                  AND product_id <> ?
                ORDER BY product_id
                LIMIT 1
                """,
                Long.class,
                minStockQuantity,
                excludedProductId);
    }

    /**
     * 테스트 의도: "특정 조건의 대표 1건"으로 빈 장바구니 사용자를 안정적으로 선택한다.
     */
    public Long findRepresentativeActiveUserIdWithEmptyCart() {
        return jdbcTemplate.queryForObject(
                """
                SELECT u.user_id
                FROM users u
                WHERE u.is_active = true
                  AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id
                LIMIT 1
                """,
                Long.class);
    }
}
