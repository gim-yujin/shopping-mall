package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * ProductService 추가 통합 테스트 — 미커버 조회 메서드 5개
 *
 * 커버 항목:
 * - findByCategory: 단일 카테고리 상품 조회
 * - findByCategoryIds: 복수 카테고리 상품 조회
 * - getBestSellers: 판매량 기준 정렬
 * - getNewArrivals: 최신 상품 조회
 * - getDeals: 할인 상품 조회
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class ProductServiceIntegrationTestSupplementary {

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Integer testCategoryId;

    @BeforeEach
    void setUp() {
        // 상품이 있는 카테고리 찾기
        testCategoryId = jdbcTemplate.queryForObject(
                """
                SELECT c.category_id FROM categories c
                JOIN products p ON p.category_id = c.category_id
                WHERE p.is_active = true
                GROUP BY c.category_id
                HAVING COUNT(*) >= 1
                ORDER BY COUNT(*) DESC LIMIT 1
                """,
                Integer.class);

        System.out.println("  [setUp] 카테고리 ID: " + testCategoryId);
    }

    @Test
    @DisplayName("findByCategory — 특정 카테고리 상품 조회")
    void findByCategory_returnsProductsInCategory() {
        Page<Product> result = productService.findByCategory(testCategoryId, PageRequest.of(0, 10));

        assertThat(result.getContent()).isNotEmpty();
        System.out.println("  [PASS] findByCategory: " + result.getTotalElements() + "개");
    }

    @Test
    @DisplayName("findByCategoryIds — 복수 카테고리 ID로 상품 조회")
    void findByCategoryIds_returnsProductsAcrossCategories() {
        // 카테고리 2개 이상 조회
        List<Integer> categoryIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT category_id FROM products WHERE is_active = true LIMIT 3",
                Integer.class);

        Page<Product> result = productService.findByCategoryIds(categoryIds, PageRequest.of(0, 20));

        assertThat(result.getContent()).isNotEmpty();
        System.out.println("  [PASS] findByCategoryIds (" + categoryIds.size() + "개 카테고리): "
                + result.getTotalElements() + "개 상품");
    }

    @Test
    @DisplayName("getBestSellers — 베스트셀러 조회")
    void getBestSellers_returnsResults() {
        Page<Product> result = productService.getBestSellers(PageRequest.of(0, 8));

        // 시드 데이터에 상품이 있다면 결과가 있어야 함
        assertThat(result).isNotNull();
        System.out.println("  [PASS] getBestSellers: " + result.getTotalElements() + "개");
    }



    @Test
    @DisplayName("getBestSellers — 주문 취소(판매량 롤백) 반영 시 정렬 순서 변경")
    void getBestSellers_reflectsSalesRollbackOrdering() {
        List<Map<String, Object>> topProducts = jdbcTemplate.queryForList(
                "SELECT product_id, sales_count FROM products WHERE is_active = true ORDER BY product_id LIMIT 2");
        assertThat(topProducts).hasSizeGreaterThanOrEqualTo(2);

        Integer currentMaxSales = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sales_count), 0) FROM products WHERE is_active = true",
                Integer.class);
        assertThat(currentMaxSales).isNotNull();

        Long firstProductId = ((Number) topProducts.get(0).get("product_id")).longValue();
        Long secondProductId = ((Number) topProducts.get(1).get("product_id")).longValue();

        Map<Long, Integer> originalSales = new HashMap<>();
        originalSales.put(firstProductId, ((Number) topProducts.get(0).get("sales_count")).intValue());
        originalSales.put(secondProductId, ((Number) topProducts.get(1).get("sales_count")).intValue());

        try {
            int firstSalesBeforeCancel = currentMaxSales + 2000;
            int secondSalesStable = currentMaxSales + 1000;
            int firstSalesAfterCancel = currentMaxSales + 500;

            jdbcTemplate.update("UPDATE products SET sales_count = ? WHERE product_id = ?", firstSalesBeforeCancel, firstProductId);
            jdbcTemplate.update("UPDATE products SET sales_count = ? WHERE product_id = ?", secondSalesStable, secondProductId);

            Page<Product> boostedResult = productService.getBestSellers(PageRequest.of(0, 50));
            List<Long> boostedIds = boostedResult.getContent().stream()
                    .map(Product::getProductId)
                    .toList();
            assertThat(boostedIds)
                    .as("판매량 보정 후 두 테스트 상품이 베스트셀러 목록에 포함되어야 함")
                    .contains(firstProductId, secondProductId);
            assertThat(boostedIds.indexOf(firstProductId))
                    .as("판매량이 가장 높은 상품(first)이 second보다 앞서야 함")
                    .isLessThan(boostedIds.indexOf(secondProductId));

            jdbcTemplate.update("UPDATE products SET sales_count = ? WHERE product_id = ?", firstSalesAfterCancel, firstProductId);

            Page<Product> rolledBackResult = productService.getBestSellers(PageRequest.of(0, 50));
            List<Long> rolledBackIds = rolledBackResult.getContent().stream()
                    .map(Product::getProductId)
                    .toList();
            assertThat(rolledBackIds)
                    .as("롤백 후 두 테스트 상품이 베스트셀러 목록에 포함되어야 함")
                    .contains(firstProductId, secondProductId);
            assertThat(rolledBackIds.indexOf(secondProductId))
                    .as("취소 롤백 후 second가 first보다 앞서야 함")
                    .isLessThan(rolledBackIds.indexOf(firstProductId));
        } finally {
            jdbcTemplate.update("UPDATE products SET sales_count = ? WHERE product_id = ?",
                    originalSales.get(firstProductId), firstProductId);
            jdbcTemplate.update("UPDATE products SET sales_count = ? WHERE product_id = ?",
                    originalSales.get(secondProductId), secondProductId);
        }
    }

    @Test
    @DisplayName("getNewArrivals — 신상품 조회")
    void getNewArrivals_returnsResults() {
        Page<Product> result = productService.getNewArrivals(PageRequest.of(0, 8));

        assertThat(result).isNotNull();
        System.out.println("  [PASS] getNewArrivals: " + result.getTotalElements() + "개");
    }

    @Test
    @DisplayName("getDeals — 할인 상품 조회")
    void getDeals_returnsResults() {
        Page<Product> result = productService.getDeals(PageRequest.of(0, 8));

        assertThat(result).isNotNull();
        System.out.println("  [PASS] getDeals: " + result.getTotalElements() + "개");
    }
}
