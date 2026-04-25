package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.dto.FlashSalePurchaseResponse;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.service.OrderCancellationService;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 23-5] 플래시 세일 주문 취소 보상 경로 통합 테스트.
 *
 * <h3>검증 항목 (§13-2 #6 해소)</h3>
 * <ol>
 *   <li>products.stock_quantity 가 인플레되지 않는다 — 차감하지 않은 재고를 복원하면 안 됨</li>
 *   <li>flash_sale_items.remaining_quantity 가 정확히 +1 복원된다</li>
 *   <li>flash_sale_purchases 행이 삭제되어 같은 사용자가 재시도할 수 있다</li>
 *   <li>orders.order_status = CANCELLED, refunded_amount = final_amount</li>
 *   <li>주문 라인 수량은 1로 유지(부분 취소가 아님), order_origin = FLASH_SALE 유지</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class FlashSaleCancellationIT {

    @Autowired
    private FlashSaleCommandService commandService;

    @Autowired
    private OrderCancellationService orderCancellationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;

    private Long userId;
    private Long productId;
    private int productStockBaseline;
    private Long flashSaleId;
    private Long flashSaleItemId;

    private static final int STOCK = 5;
    private static final BigDecimal SALE_PRICE = new BigDecimal("9900.00");

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        userId = fixture.createActiveUser();
        productId = fixture.createActiveProduct(1000);
        productStockBaseline = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, productId);

        LocalDateTime now = LocalDateTime.now();
        flashSaleId = jdbcTemplate.queryForObject(
                """
                INSERT INTO flash_sales (title, status, start_time, end_time, created_at, version)
                VALUES (?, 'ACTIVE', ?, ?, ?, 0)
                RETURNING flash_sale_id
                """,
                Long.class,
                "취소 보상 IT 세일",
                now.minusMinutes(1),
                now.plusHours(1),
                now);
        flashSaleItemId = jdbcTemplate.queryForObject(
                """
                INSERT INTO flash_sale_items
                    (flash_sale_id, product_id, sale_price, allocated_quantity,
                     remaining_quantity, per_user_limit, version)
                VALUES (?, ?, ?, ?, ?, 1, 0)
                RETURNING flash_sale_item_id
                """,
                Long.class,
                flashSaleId, productId, SALE_PRICE, STOCK, STOCK);
    }

    @AfterEach
    void tearDown() {
        if (flashSaleId != null) {
            jdbcTemplate.update("DELETE FROM flash_sale_purchases WHERE flash_sale_id = ?", flashSaleId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN "
                    + "(SELECT order_id FROM orders WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
        }
        if (flashSaleItemId != null) {
            jdbcTemplate.update("DELETE FROM flash_sale_items WHERE flash_sale_item_id = ?", flashSaleItemId);
        }
        if (flashSaleId != null) {
            jdbcTemplate.update("DELETE FROM flash_sales WHERE flash_sale_id = ?", flashSaleId);
        }
        fixture.cleanup();
    }

    @Test
    @DisplayName("플래시 세일 주문 취소 → products.stock_quantity 불변, remaining 복원, purchase 삭제")
    void cancelFlashSaleOrder_compensatesCorrectly() {
        // 1) 구매 실행 — remaining 5 → 4, purchase 1건, order 1건
        FlashSalePurchaseResponse response = commandService.purchase(flashSaleId, flashSaleItemId, userId);
        Long orderId = response.orderId();

        assertRemainingQuantity(STOCK - 1);
        assertPurchaseCount(1);

        // 2) 사용자 취소 호출
        orderCancellationService.cancelOrder(orderId, userId);

        // 3) 보상 정합성 검증
        // 3-1) 일반 재고는 인플레되지 않음
        Integer productStockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, productId);
        assertThat(productStockAfter)
                .as("products.stock_quantity 는 차감된 적이 없으므로 복원도 없어야 함")
                .isEqualTo(productStockBaseline);

        // 3-2) 세일 잔여 수량은 +1 복원
        assertRemainingQuantity(STOCK);

        // 3-3) flash_sale_purchases 행 삭제 — UNIQUE 해제로 재시도 가능
        assertPurchaseCount(0);

        // 3-4) orders 상태 = CANCELLED, refunded_amount = final_amount
        String statusAfter = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?", String.class, orderId);
        assertThat(statusAfter).isEqualTo(OrderStatus.CANCELLED.name());

        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?", BigDecimal.class, orderId);
        BigDecimal finalAmt = jdbcTemplate.queryForObject(
                "SELECT final_amount FROM orders WHERE order_id = ?", BigDecimal.class, orderId);
        assertThat(refunded).as("플래시 세일 주문 취소 시 finalAmount 전액 환불 기록").isEqualByComparingTo(finalAmt);

        // 3-5) order_origin은 그대로 FLASH_SALE 유지(취소 후에도 마커 보존)
        String origin = jdbcTemplate.queryForObject(
                "SELECT order_origin FROM orders WHERE order_id = ?", String.class, orderId);
        assertThat(origin).isEqualTo("FLASH_SALE");
    }

    @Test
    @DisplayName("취소 후 같은 사용자가 다시 구매 시도 → 정상 성공")
    void cancelFlashSaleOrder_allowsReentry() {
        FlashSalePurchaseResponse first = commandService.purchase(flashSaleId, flashSaleItemId, userId);
        orderCancellationService.cancelOrder(first.orderId(), userId);

        // remaining 정상 복원 + UNIQUE 해제 → 재구매 성공해야 함
        FlashSalePurchaseResponse second = commandService.purchase(flashSaleId, flashSaleItemId, userId);
        assertThat(second.orderId()).isNotEqualTo(first.orderId());
        assertRemainingQuantity(STOCK - 1);
        assertPurchaseCount(1);
    }

    private void assertRemainingQuantity(int expected) {
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM flash_sale_items WHERE flash_sale_item_id = ?",
                Integer.class, flashSaleItemId);
        assertThat(remaining).as("flash_sale_items.remaining_quantity").isEqualTo(expected);
    }

    private void assertPurchaseCount(int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_purchases WHERE flash_sale_id = ?",
                Integer.class, flashSaleId);
        assertThat(count).as("flash_sale_purchases 행 수").isEqualTo(expected);
    }
}
