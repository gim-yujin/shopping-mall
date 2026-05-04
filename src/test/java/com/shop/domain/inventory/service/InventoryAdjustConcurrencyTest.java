package com.shop.domain.inventory.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 재고 조정 동시성 테스트
 *
 * InventoryService.adjustStock()은 productRepository.findByIdWithLock()으로
 * 비관적 잠금을 잡은 뒤 재고를 변경한다. 이 테스트는 잠금이 올바르게 직렬화하는지 검증한다.
 *
 * 시나리오 1 — 다수 관리자 동시 입고
 *   재고 0인 상품에 10개 스레드가 각 10개씩 동시 입고
 *   기대: 최종 재고 정확히 100개 (Lost Update 없음)
 *
 * 시나리오 2 — 동시 출고 → 재고 부족 방지
 *   재고 50인 상품에 10개 스레드가 각 10개씩 동시 출고
 *   기대: 최대 5건 성공, 나머지 재고 부족 실패, 최종 재고 ≥ 0
 *
 * 시나리오 3 — 입고 + 출고 혼합 동시 실행
 *   재고 100인 상품에 입고(+10) 5건 + 출고(-10) 5건 동시 실행
 *   기대: 최종 재고 == 100 + 입고성공분 - 출고성공분
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
class InventoryAdjustConcurrencyTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testProductId;
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        Integer categoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM categories LIMIT 1", Integer.class);

        jdbcTemplate.update("""
                INSERT INTO products (product_name, category_id, description, price, original_price,
                    stock_quantity, sales_count, view_count, rating_avg, review_count,
                    is_active, created_at, updated_at, version)
                VALUES ('재고동시성테스트', ?, '테스트', 10000, 15000, 0, 0, 0, 0, 0,
                    true, NOW(), NOW(), 0)
                """, categoryId);

        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE product_name = '재고동시성테스트' ORDER BY product_id DESC LIMIT 1",
                Long.class);

        adminUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE role = 'ROLE_ADMIN' ORDER BY user_id LIMIT 1",
                Long.class);
    }

    @AfterEach
    void tearDown() {
        if (testProductId != null) {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE payload LIKE ?",
                    "%" + testProductId + "%");
            jdbcTemplate.update("DELETE FROM product_inventory_history WHERE product_id = ?", testProductId);
            jdbcTemplate.update("DELETE FROM product_images WHERE product_id = ?", testProductId);
            jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", testProductId);
        }
    }

    private void setStock(int quantity) {
        jdbcTemplate.update("UPDATE products SET stock_quantity = ?, updated_at = NOW() WHERE product_id = ?",
                quantity, testProductId);
    }

    private int getStock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?", Integer.class, testProductId);
    }

    // =========================================================================
    // 시나리오 1: 다수 관리자 동시 입고 → Lost Update 방지
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 재고 0, 10스레드 × +10 동시 입고 → 최종 재고 100")
    void concurrentStockIncrease_preventsLostUpdate() throws InterruptedException {
        int initialStock = 0;
        int increaseAmount = 10;
        int threadCount = 10;
        int expectedFinal = initialStock + (increaseAmount * threadCount);

        setStock(initialStock);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    inventoryService.adjustStock(testProductId, increaseAmount,
                            "동시성테스트 입고 #" + attempt, adminUserId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                    errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalStock = getStock();
        int historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_inventory_history WHERE product_id = ?",
                Integer.class, testProductId);

        System.out.println("========================================");
        System.out.println("[시나리오 1: 동시 입고]");
        System.out.println("  초기 재고:   " + initialStock);
        System.out.println("  입고 단위:   +" + increaseAmount + " × " + threadCount + "스레드");
        System.out.println("  성공:        " + successCount.get() + "건");
        System.out.println("  기타 실패:   " + otherFailCount.get() + "건");
        System.out.println("  최종 재고:   " + finalStock + " (기대: " + expectedFinal + ")");
        System.out.println("  이력 건수:   " + historyCount);
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 모든 입고 성공
        assertThat(successCount.get())
                .as("모든 입고가 성공해야 합니다")
                .isEqualTo(threadCount);

        // ② 최종 재고 정확
        assertThat(finalStock)
                .as("Lost Update가 발생하면 재고가 %d보다 적습니다 (현재: %d)", expectedFinal, finalStock)
                .isEqualTo(expectedFinal);

        // ③ 이력 건수 == 성공 건수
        assertThat(historyCount)
                .as("재고 이력이 성공 건수와 일치해야 합니다")
                .isEqualTo(successCount.get());

        // ④ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 2: 동시 출고 → 재고 부족 방지
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 재고 50, 10스레드 × -10 출고 → 최대 5건 성공, 재고 ≥ 0")
    void concurrentStockDecrease_preventsNegativeStock() throws InterruptedException {
        int initialStock = 50;
        int decreaseAmount = 10;
        int threadCount = 10;
        int maxExpectedSuccess = initialStock / decreaseAmount;

        setStock(initialStock);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    inventoryService.adjustStock(testProductId, -decreaseAmount,
                            "동시성테스트 출고 #" + attempt, adminUserId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("부족")) {
                        insufficientCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalStock = getStock();

        System.out.println("========================================");
        System.out.println("[시나리오 2: 동시 출고]");
        System.out.println("  초기 재고:       " + initialStock);
        System.out.println("  출고 단위:       -" + decreaseAmount + " × " + threadCount + "스레드");
        System.out.println("  성공:            " + successCount.get() + "건");
        System.out.println("  재고 부족 실패:  " + insufficientCount.get() + "건");
        System.out.println("  기타 실패:       " + otherFailCount.get() + "건");
        System.out.println("  최종 재고:       " + finalStock);
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 재고는 절대 음수가 되면 안 된다
        assertThat(finalStock)
                .as("재고가 음수가 되면 안 됩니다 (현재: %d)", finalStock)
                .isGreaterThanOrEqualTo(0);

        // ② 성공 건수 × 출고량 == 초기재고 - 최종재고
        assertThat(successCount.get() * decreaseAmount)
                .as("성공 건수 × 출고량 == 차감된 총량")
                .isEqualTo(initialStock - finalStock);

        // ③ 최대 성공 건수 초과 불가
        assertThat(successCount.get())
                .as("최대 %d건까지만 성공해야 합니다", maxExpectedSuccess)
                .isLessThanOrEqualTo(maxExpectedSuccess);

        // ④ 모든 스레드가 성공 또는 재고부족으로 종료
        assertThat(successCount.get() + insufficientCount.get())
                .as("성공 + 재고부족 = 전체 스레드")
                .isEqualTo(threadCount);

        // ⑤ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 3: 입고 + 출고 혼합 → 정합성 검증
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: 재고 100, 입고(+10) 5건 + 출고(-10) 5건 → 정합성 유지")
    void concurrentMixedAdjust_maintainsConsistency() throws InterruptedException {
        int initialStock = 100;
        int amount = 10;
        int increaseThreads = 5;
        int decreaseThreads = 5;
        int totalThreads = increaseThreads + decreaseThreads;

        setStock(initialStock);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalThreads);

        AtomicInteger increaseSuccess = new AtomicInteger(0);
        AtomicInteger decreaseSuccess = new AtomicInteger(0);
        AtomicInteger decreaseFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // 입고 스레드
        for (int i = 0; i < increaseThreads; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    inventoryService.adjustStock(testProductId, amount,
                            "혼합테스트 입고 #" + attempt, adminUserId);
                    increaseSuccess.incrementAndGet();
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                    errors.add("입고#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        // 출고 스레드
        for (int i = 0; i < decreaseThreads; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    inventoryService.adjustStock(testProductId, -amount,
                            "혼합테스트 출고 #" + attempt, adminUserId);
                    decreaseSuccess.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("부족")) {
                        decreaseFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("출고#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalStock = getStock();
        int expectedStock = initialStock + (increaseSuccess.get() * amount) - (decreaseSuccess.get() * amount);

        System.out.println("========================================");
        System.out.println("[시나리오 3: 입고 + 출고 혼합]");
        System.out.println("  초기 재고:       " + initialStock);
        System.out.println("  입고 성공:       " + increaseSuccess.get() + "건 (+" + increaseSuccess.get() * amount + ")");
        System.out.println("  출고 성공:       " + decreaseSuccess.get() + "건 (-" + decreaseSuccess.get() * amount + ")");
        System.out.println("  재고 부족 실패:  " + decreaseFailCount.get() + "건");
        System.out.println("  기타 실패:       " + otherFailCount.get() + "건");
        System.out.println("  최종 재고:       " + finalStock + " (기대: " + expectedStock + ")");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 재고가 음수가 아님
        assertThat(finalStock)
                .as("재고가 음수가 되면 안 됩니다")
                .isGreaterThanOrEqualTo(0);

        // ② 최종 재고 == 초기 + 입고분 - 출고분
        assertThat(finalStock)
                .as("최종 재고가 연산 결과와 일치해야 합니다")
                .isEqualTo(expectedStock);

        // ③ 입고는 모두 성공
        assertThat(increaseSuccess.get())
                .as("입고는 모두 성공해야 합니다")
                .isEqualTo(increaseThreads);

        // ④ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }
}
