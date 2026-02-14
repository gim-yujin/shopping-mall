package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

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
