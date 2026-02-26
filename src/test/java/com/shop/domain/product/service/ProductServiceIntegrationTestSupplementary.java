package com.shop.domain.product.service;

import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.global.cache.CacheKeyGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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

    @Autowired
    private CacheManager cacheManager;

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

    private void evictBestSellersCache() {
        if (cacheManager.getCache("bestSellers") != null) {
            cacheManager.getCache("bestSellers").clear();
        }
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

            evictBestSellersCache();
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

            evictBestSellersCache();
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


    @Test
    @DisplayName("updateProduct 이후 productList 캐시는 비워져 다음 조회가 miss 처리된다")
    void updateProduct_evictsProductListCache() {
        Cache productListCache = cacheManager.getCache("productList");
        assertThat(productListCache).isNotNull();

        productListCache.clear();

        int page = 0;
        int size = 8;
        String sort = "best";
        String cacheKey = page + ":" + size + ":" + sort;

        productService.findAllSorted(page, size, sort);
        assertThat(productListCache.get(cacheKey))
                .as("초기 조회 후 productList 캐시 엔트리가 생성되어야 함")
                .isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT p.product_id, p.category_id, p.product_name, p.description,
                       p.price, p.original_price, p.stock_quantity
                FROM products p
                WHERE p.is_active = true
                ORDER BY p.product_id
                LIMIT 1
                """);

        Long productId = ((Number) row.get("product_id")).longValue();

        AdminProductRequest request = new AdminProductRequest();
        request.setCategoryId(((Number) row.get("category_id")).intValue());
        request.setProductName(row.get("product_name") + " (캐시무효화)");
        request.setDescription((String) row.get("description"));
        request.setPrice((java.math.BigDecimal) row.get("price"));
        request.setOriginalPrice((java.math.BigDecimal) row.get("original_price"));
        request.setStockQuantity(((Number) row.get("stock_quantity")).intValue());

        productService.updateProduct(productId, request);

        assertThat(productListCache.get(cacheKey))
                .as("상품 수정 후 productList 캐시가 제거되어야 함")
                .isNull();

        productService.findAllSorted(page, size, sort);

        assertThat(productListCache.get(cacheKey))
                .as("캐시 miss 이후 재조회 시 productList 캐시가 다시 채워져야 함")
                .isNotNull();
    }

    @Test
    @DisplayName("홈 화면 pageable(0,8) 규격과 캐시 키가 일치해야 한다")
    void homePageableSpec_matchesCacheKey() {
        PageRequest homePageable = PageRequest.of(0, 8);

        productService.getBestSellers(homePageable);
        productService.getNewArrivals(homePageable);
        productService.getDeals(homePageable);

        String expectedKey = CacheKeyGenerator.pageable(homePageable);

        assertThat(expectedKey).isEqualTo("0:8:UNSORTED");
        assertThat(cacheManager.getCache("bestSellers").get(expectedKey)).isNotNull();
        assertThat(cacheManager.getCache("newArrivals").get(expectedKey)).isNotNull();
        assertThat(cacheManager.getCache("deals").get(expectedKey)).isNotNull();
    }

    @Test
    @DisplayName("서로 다른 pageable 요청은 서로 다른 캐시 엔트리를 사용한다")
    void pageableRequests_useDifferentCacheEntries() {
        Cache bestSellersCache = cacheManager.getCache("bestSellers");
        Cache newArrivalsCache = cacheManager.getCache("newArrivals");
        Cache dealsCache = cacheManager.getCache("deals");

        assertThat(bestSellersCache).isNotNull();
        assertThat(newArrivalsCache).isNotNull();
        assertThat(dealsCache).isNotNull();

        bestSellersCache.clear();
        newArrivalsCache.clear();
        dealsCache.clear();

        PageRequest firstPageable = PageRequest.of(0, 8);
        PageRequest secondPageable = PageRequest.of(1, 8);

        productService.getBestSellers(firstPageable);
        productService.getBestSellers(secondPageable);
        productService.getNewArrivals(firstPageable);
        productService.getNewArrivals(secondPageable);
        productService.getDeals(firstPageable);
        productService.getDeals(secondPageable);

        String firstKey = CacheKeyGenerator.pageable(firstPageable);
        String secondKey = CacheKeyGenerator.pageable(secondPageable);

        assertThat(firstKey).isNotEqualTo(secondKey);
        assertThat(bestSellersCache.get(firstKey)).isNotNull();
        assertThat(bestSellersCache.get(secondKey)).isNotNull();
        assertThat(newArrivalsCache.get(firstKey)).isNotNull();
        assertThat(newArrivalsCache.get(secondKey)).isNotNull();
        assertThat(dealsCache.get(firstKey)).isNotNull();
        assertThat(dealsCache.get(secondKey)).isNotNull();
    }

}
