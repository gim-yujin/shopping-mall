package com.shop.domain.product.service;

import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.testsupport.ActiveDataLookupHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ViewCountService viewCountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveDataLookupHelper activeDataLookupHelper;

    private Long testProductId;
    private int originalViewCount;

    @BeforeEach
    void setUp() {
        // 테스트 의도: "아무거나 1건"의 활성 상품으로 최소 PK 1건을 안정적으로 선택.
        testProductId = activeDataLookupHelper.findRepresentativeActiveProductId();
        originalViewCount = jdbcTemplate.queryForObject(
                "SELECT view_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "UPDATE products SET view_count = ? WHERE product_id = ?",
                originalViewCount, testProductId);
    }

    @Test
    @DisplayName("findByIdCached + incrementAsync - 조회수 1 증가")
    void findByIdCached_withSeparateIncrement_incrementsViewCount() {
        // [P0 FIX] 검증: 캐시 메서드와 조회수 증가가 분리되어 매 요청마다 정확히 증가하는지 확인.
        // [P2-7] findByIdCached가 CachedProductDetail 불변 DTO를 반환함을 검증.
        CachedProductDetail product = productService.findByIdCached(testProductId);
        viewCountService.incrementAsync(testProductId);

        // @Async로 변경된 viewCount UPDATE가 별도 스레드에서 완료되어 DB 상태가 기대값이 될 때까지 폴링
        await()
                .atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    int current = jdbcTemplate.queryForObject(
                            "SELECT view_count FROM products WHERE product_id = ?",
                            Integer.class, testProductId);

                    assertThat(current)
                            .withFailMessage("조회수 반영 대기 타임아웃 - productId=%s, original=%s, expected=%s, current=%s",
                                    testProductId, originalViewCount, originalViewCount + 1, current)
                            .isEqualTo(originalViewCount + 1);
                });

        assertThat(product.productId())
                .as("조회한 상품 ID가 요청값과 일치해야 함")
                .isEqualTo(testProductId);
    }
}
