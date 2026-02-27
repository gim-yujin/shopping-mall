package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderSameUserConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testProductId;
    private int originalStock;
    private int originalSalesCount;
    private Map<String, Object> originalUserState;

    private String marker;

    @BeforeEach
    void setUp() {
        marker = "LOCK_TEST_" + System.currentTimeMillis();

        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT user_id FROM users
                WHERE is_active = true AND role = 'ROLE_USER'
                ORDER BY user_id
                LIMIT 1
                """,
                Long.class);

        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 10 ORDER BY product_id LIMIT 1",
                Long.class);

        originalUserState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserId);

        Map<String, Object> productState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);
        originalStock = ((Number) productState.get("stock_quantity")).intValue();
        originalSalesCount = ((Number) productState.get("sales_count")).intValue();

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                testUserId, testProductId, now, now);
    }

    @AfterEach
    void tearDown() {
        List<Long> createdOrderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM orders WHERE user_id = ? AND shipping_address = ?",
                Long.class, testUserId, marker);

        if (!createdOrderIds.isEmpty()) {
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE product_id = ? AND created_by = ? AND reason = 'ORDER'",
                    testProductId, testUserId);
            jdbcTemplate.update(
                    "DELETE FROM orders WHERE user_id = ? AND shipping_address = ?",
                    testUserId, marker);
        }

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalStock, originalSalesCount, testProductId);

        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserState.get("total_spent"),
                originalUserState.get("point_balance"),
                originalUserState.get("tier_id"),
                testUserId);
    }

    @Test
    @DisplayName("같은 사용자의 동시 주문 요청은 직렬화되어 1건만 성공한다")
    void createOrder_sameUserConcurrent_onlyOneSucceeds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger emptyCartFailureCount = new AtomicInteger(0);
        List<Throwable> unexpected = new ArrayList<>();

        Runnable task = () -> {
            ready.countDown();
            try {
                start.await();
                orderService.createOrder(testUserId, new OrderCreateRequest(
                        marker,
                        "동시성테스터",
                        "010-1234-5678",
                        "CARD",
                        BigDecimal.ZERO,
                        null, null, null
                ));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                if ("EMPTY_CART".equals(e.getCode())) {
                    emptyCartFailureCount.incrementAndGet();
                } else {
                    synchronized (unexpected) {
                        unexpected.add(e);
                    }
                }
            } catch (Throwable t) {
                synchronized (unexpected) {
                    unexpected.add(t);
                }
            } finally {
                done.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Integer createdOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND shipping_address = ?",
                Integer.class, testUserId, marker);

        assertThat(unexpected).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(emptyCartFailureCount.get()).isEqualTo(1);
        assertThat(createdOrders).isEqualTo(1);
    }
}
