package com.shop.domain.inventory.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.testsupport.TestDataFactory;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class InventoryServiceIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private Long testProductId;
    private Long testUserId;
    private int originalStock;
    private String reasonTag;
    private TestDataFactory.FixtureContext fixtureContext;

    @BeforeEach
    void setUp() {
        fixtureContext = testDataFactory.newContext();
        testUserId = fixtureContext.createActiveUser();
        testProductId = fixtureContext.createActiveProduct(30);
        originalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        reasonTag = "TEST_STOCK_IN_" + System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE product_id = ? AND reason = ?",
                testProductId, reasonTag);
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ? WHERE product_id = ?",
                originalStock, testProductId);
        fixtureContext.cleanup();
    }

    @Test
    @DisplayName("adjustStock - 입고 처리 후 재고/이력 반영")
    void adjustStock_increase_updatesStockAndHistory() {
        inventoryService.adjustStock(testProductId, 4, reasonTag, testUserId);

        int afterStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        assertThat(afterStock)
                .as("입고 수량만큼 재고가 증가해야 함")
                .isEqualTo(originalStock + 4);

        Page<ProductInventoryHistory> history = inventoryService.getHistory(testProductId, PageRequest.of(0, 20));
        assertThat(history.getContent())
                .as("재고 이력에서 방금 저장한 reason을 찾을 수 있어야 함")
                .anyMatch(h -> reasonTag.equals(h.getReason())
                        && "IN".equals(h.getChangeType())
                        && h.getChangeAmount() == 4);
    }
}
