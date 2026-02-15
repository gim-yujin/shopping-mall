package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
    private JdbcTemplate jdbcTemplate;

    private Long testProductId;
    private int originalViewCount;

    @BeforeEach
    void setUp() {
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true LIMIT 1",
                Long.class);
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
    @DisplayName("findByIdAndIncrementView - 조회수 1 증가")
    void findByIdAndIncrementView_incrementsViewCount() throws InterruptedException {
        Product product = productService.findByIdAndIncrementView(testProductId);

        // @Async로 변경된 viewCount UPDATE가 별도 스레드에서 완료될 때까지 대기
        Thread.sleep(500);

        int after = jdbcTemplate.queryForObject(
                "SELECT view_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        assertThat(product.getProductId())
                .as("조회한 상품 ID가 요청값과 일치해야 함")
                .isEqualTo(testProductId);
        assertThat(after)
                .as("조회수는 1 증가해야 함")
                .isEqualTo(originalViewCount + 1);
    }
}
