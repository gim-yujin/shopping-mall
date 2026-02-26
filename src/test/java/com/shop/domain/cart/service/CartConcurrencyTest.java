package com.shop.domain.cart.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장바구니 동시성 테스트
 *
 * 시나리오 1 — 같은 상품 동시 추가 (Duplicate Insert)
 *   같은 사용자가 같은 상품을 5회 동시 addToCart 호출
 *   위험: UNIQUE(user_id, product_id) 위반으로 500 에러 노출
 *   기대: 에러 없이 정상 처리, 장바구니에 1건만 존재
 *
 * 시나리오 2 — 수량 동시 증가 (Lost Update)
 *   장바구니에 이미 담긴 상품(수량 1)에 대해 5개 스레드가 동시에 addToCart(quantity=1)
 *   위험: 모두 기존 수량 1을 읽고 → 1+1=2로 설정 → 최종 2 (기대: 6)
 *   기대: 최종 수량 = 1(기존) + 5(추가) = 6
 *
 * 시나리오 3 — MAX_CART_ITEMS 초과 (Limit Bypass)
 *   장바구니에 49개 상품이 있을 때 5개 스레드가 동시에 서로 다른 상품 추가
 *   위험: 모두 count=49로 읽고 → 49 < 50 통과 → 5개 모두 INSERT → 총 54개
 *   기대: 최대 50개까지만 허용

 * 시나리오 4 — updateQuantity vs addToCart 동시 요청
 *   같은 사용자/같은 상품에 대해 updateQuantity(10)와 addToCart(+1)를 동시에 실행
 *   기대: 사용자 락으로 직렬화되어 최종 수량이 10 또는 11 중 하나의 일관된 값
 *
 * 시나리오 5 — updateQuantity vs removeFromCart 동시 요청
 *   같은 사용자/같은 상품에 대해 updateQuantity(10)와 removeFromCart를 동시에 실행
 *   기대: 사용자 락으로 직렬화되어 최종 상태가 "삭제됨" 또는 "수량 10" 중 하나로 수렴
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class CartConcurrencyTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;

    // =========================================================================
    // 시나리오 1: 같은 상품 동시 추가
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 같은 상품 5회 동시 추가 → UNIQUE 위반 없이 정상 처리")
    void duplicateAdd_noErrors() throws InterruptedException {
        // Given
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        // 기존 장바구니 항목 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);

        System.out.println("========================================");
        System.out.println("[시나리오 1: 같은 상품 동시 추가]");
        System.out.println("  사용자 ID: " + testUserId);
        System.out.println("  상품 ID: " + productId);
        System.out.println("========================================");

        // When: 5개 스레드가 동시에 같은 상품 추가
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cartService.addToCart(testUserId, productId, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        Integer cartCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        Integer totalQuantity = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  성공: " + successCount.get() + "회");
        System.out.println("  실패: " + failCount.get() + "회");
        System.out.println("  DB 장바구니 행 수: " + cartCount + "건 (기대: 1건)");
        System.out.println("  총 수량: " + totalQuantity + " (기대: 5)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);

        // ① 에러 없음
        assertThat(failCount.get())
                .as("동시 추가 시 에러가 발생하면 안 됩니다: %s", errors)
                .isEqualTo(0);

        // ② 장바구니에 1건만 존재 (중복 행 없음)
        assertThat(cartCount)
                .as("같은 상품은 장바구니에 1건만 존재해야 합니다 (현재: %d건)", cartCount)
                .isEqualTo(1);

        // ③ 총 수량 = 5 (1 × 5회 추가)
        assertThat(totalQuantity)
                .as("5회 추가했으므로 총 수량은 5여야 합니다 (현재: %d)", totalQuantity)
                .isEqualTo(5);
    }

    // =========================================================================
    // 시나리오 2: 수량 동시 증가 (Lost Update)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 이미 담긴 상품에 5회 동시 수량 추가 → Lost Update 방지")
    void concurrentQuantityIncrease_noLostUpdate() throws InterruptedException {
        // Given
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        // 장바구니에 수량 1로 미리 추가
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, NOW(), NOW())",
                testUserId, productId);

        int initialQuantity = 1;

        System.out.println("========================================");
        System.out.println("[시나리오 2: 수량 동시 증가]");
        System.out.println("  사용자 ID: " + testUserId);
        System.out.println("  상품 ID: " + productId);
        System.out.println("  초기 수량: " + initialQuantity);
        System.out.println("  동시 추가: 5회 × 수량 1");
        System.out.println("  기대 최종 수량: " + (initialQuantity + 5));
        System.out.println("========================================");

        // When: 5개 스레드가 동시에 수량 1씩 추가
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cartService.addToCart(testUserId, productId, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        Integer finalQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        int expectedQuantity = initialQuantity + 5;

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  성공: " + successCount.get() + "회");
        System.out.println("  실패: " + failCount.get() + "회");
        System.out.println("  최종 수량: " + finalQuantity + " (기대: " + expectedQuantity + ")");
        System.out.println("  Lost Update 횟수: " + (expectedQuantity - finalQuantity) + "회");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);

        // ① 최종 수량 = 초기(1) + 5회 추가 = 6
        assertThat(finalQuantity)
                .as("초기 수량(1) + 5회 추가 = 6이어야 합니다 (현재: %d, Lost Update: %d회)",
                        finalQuantity, expectedQuantity - finalQuantity)
                .isEqualTo(expectedQuantity);
    }

    // =========================================================================
    // 시나리오 3: MAX_CART_ITEMS 초과
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: 49개 상태에서 5개 상품 동시 추가 → 최대 50개 제한 준수")
    void maxCartItems_limitEnforcement() throws InterruptedException {
        // Given
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        // 기존 장바구니 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        // 49개 상품을 장바구니에 미리 추가
        List<Long> allProductIds = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 10 ORDER BY product_id LIMIT 54",
                Long.class);

        if (allProductIds.size() < 54) {
            System.out.println("상품 수가 부족하여 테스트를 건너뜁니다 (필요: 54, 실제: " + allProductIds.size() + ")");
            return;
        }

        // 처음 49개 상품을 장바구니에 미리 넣기
        List<Long> preloadProducts = allProductIds.subList(0, 49);
        for (Long pid : preloadProducts) {
            jdbcTemplate.update(
                    "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, NOW(), NOW())",
                    testUserId, pid);
        }

        // 동시에 추가할 5개 상품 (아직 장바구니에 없는 것들)
        List<Long> newProductIds = allProductIds.subList(49, 54);

        int currentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?", Integer.class, testUserId);

        System.out.println("========================================");
        System.out.println("[시나리오 3: MAX_CART_ITEMS 초과]");
        System.out.println("  사용자 ID: " + testUserId);
        System.out.println("  현재 장바구니: " + currentCount + "개");
        System.out.println("  동시 추가 시도: " + newProductIds.size() + "개 상품");
        System.out.println("  MAX_CART_ITEMS: 50");
        System.out.println("========================================");

        // When: 5개 스레드가 동시에 서로 다른 상품 추가
        int threadCount = newProductIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final Long productId = newProductIds.get(i);
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    cartService.addToCart(testUserId, productId, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("최대")) {
                        limitFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + "(상품#" + productId + "): "
                                + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        Integer finalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?", Integer.class, testUserId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  추가 성공: " + successCount.get() + "회");
        System.out.println("  제한 초과 실패: " + limitFailCount.get() + "회");
        System.out.println("  기타 실패: " + otherFailCount.get() + "회");
        System.out.println("  최종 장바구니: " + finalCount + "개 (기대: 최대 50개)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        // ① 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);

        // ② 최종 장바구니 수 ≤ 50
        assertThat(finalCount)
                .as("장바구니는 최대 50개를 초과하면 안 됩니다 (현재: %d개)", finalCount)
                .isLessThanOrEqualTo(50);

        // ③ 성공 수 + 제한 실패 수 = 총 시도 수
        assertThat(successCount.get() + limitFailCount.get())
                .as("성공 + 제한초과 실패 = 총 시도 수여야 합니다")
                .isEqualTo(threadCount);
    }

    // =========================================================================
    // 시나리오 4: updateQuantity vs addToCart
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("시나리오 4: updateQuantity와 addToCart 동시 요청 → 일관된 최종 수량")
    void updateAndAdd_concurrentConsistency() throws InterruptedException {
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, NOW(), NOW())",
                testUserId, productId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                cartService.updateQuantity(testUserId, productId, 10);
            } catch (Exception e) {
                errors.add("updateQuantity: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                cartService.addToCart(testUserId, productId, 1);
            } catch (Exception e) {
                errors.add("addToCart: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        Integer finalQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);

        assertThat(errors)
                .as("예상치 못한 예외가 없어야 합니다")
                .isEmpty();

        assertThat(finalQuantity)
                .as("최종 수량은 직렬화 결과에 따라 10 또는 11이어야 합니다")
                .isIn(10, 11);
    }

    // =========================================================================
    // 시나리오 5: updateQuantity vs removeFromCart
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("시나리오 5: updateQuantity와 removeFromCart 동시 요청 → 일관된 최종 상태")
    void updateAndRemove_concurrentConsistency() throws InterruptedException {
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);

        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, NOW(), NOW())",
                testUserId, productId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                cartService.updateQuantity(testUserId, productId, 10);
            } catch (Exception e) {
                errors.add("updateQuantity: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                cartService.removeFromCart(testUserId, productId);
            } catch (Exception e) {
                errors.add("removeFromCart: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        Integer finalQuantity = rowCount == 0 ? null : jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId);

        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?", testUserId, productId);

        assertThat(errors)
                .as("예상치 못한 예외가 없어야 합니다")
                .isEmpty();

        assertThat(rowCount)
                .as("최종 행 수는 0 또는 1이어야 합니다")
                .isIn(0, 1);

        if (rowCount == 1) {
            assertThat(finalQuantity)
                    .as("남아 있다면 update 결과 수량(10)이어야 합니다")
                    .isEqualTo(10);
        }
    }

}
