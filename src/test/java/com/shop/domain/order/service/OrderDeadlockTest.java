package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 생성 데드락 테스트
 *
 * 시나리오: 2명의 사용자가 동일한 2개 상품을 장바구니에 넣되,
 *          추가 순서를 반대로 하여 동시에 주문
 *
 * 현재 코드의 문제:
 *   Cart 조회: ORDER BY updatedAt DESC
 *   → User A: product2를 나중에 추가 → 락 순서: product2 → product1
 *   → User B: product1을 나중에 추가 → 락 순서: product1 → product2
 *
 *   T1(A): lock(product2) ✅ → lock(product1) 대기...
 *   T2(B): lock(product1) ✅ → lock(product2) 대기...
 *   → 데드락!
 *
 * PostgreSQL은 데드락을 감지하고 하나의 트랜잭션을 강제 롤백합니다.
 * 이 테스트는 데드락 발생 여부를 감지하고, 발생 시 수정 방향을 제시합니다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderDeadlockTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 대상
    private Long userIdA;
    private Long userIdB;
    private Long productId1;
    private Long productId2;

    // 원본 상태 백업
    private Map<Long, Map<String, Object>> originalProductStates = new HashMap<>();
    private Map<Long, Map<String, Object>> originalUserStates = new HashMap<>();

    @BeforeEach
    void setUp() {
        // 1) 장바구니가 비어있는 사용자 2명 선택
        List<Long> userIds = jdbcTemplate.queryForList(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 2
                """,
                Long.class);

        if (userIds.size() < 2) {
            throw new RuntimeException("테스트 가능한 사용자가 2명 이상 필요합니다.");
        }
        userIdA = userIds.get(0);
        userIdB = userIds.get(1);

        // 사용자 원본 상태 백업
        for (Long uid : List.of(userIdA, userIdB)) {
            originalUserStates.put(uid, jdbcTemplate.queryForMap(
                    "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?", uid));
        }

        // 이전 실행 잔여 데이터 정리
        for (Long uid : List.of(userIdA, userIdB)) {
            cleanUpOrdersForUser(uid);
        }

        // 2) 재고 충분한 상품 2개 선택
        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 ORDER BY product_id LIMIT 2",
                Long.class);

        if (productIds.size() < 2) {
            throw new RuntimeException("테스트 가능한 상품이 2개 이상 필요합니다.");
        }
        productId1 = productIds.get(0);
        productId2 = productIds.get(1);

        // 상품 원본 상태 백업
        for (Long pid : List.of(productId1, productId2)) {
            originalProductStates.put(pid, jdbcTemplate.queryForMap(
                    "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?", pid));
        }

        // 3) 장바구니에 역순으로 상품 추가
        //    User A: product1 먼저 → product2 나중에 (updatedAt이 더 최신)
        //    User B: product2 먼저 → product1 나중에
        //    → ORDER BY updatedAt DESC이므로 락 순서가 역전됨

        LocalDateTime earlier = LocalDateTime.now().minusSeconds(10);
        LocalDateTime later = LocalDateTime.now();

        // User A: product1(earlier) → product2(later)
        // → 조회 시 ORDER BY updatedAt DESC → [product2, product1] 순서로 락
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdA, productId1, earlier, earlier);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdA, productId2, later, later);

        // User B: product2(earlier) → product1(later)
        // → 조회 시 ORDER BY updatedAt DESC → [product1, product2] 순서로 락
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, productId2, earlier, earlier);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, productId1, later, later);

        System.out.println("========================================");
        System.out.println("[데드락 테스트 준비 완료]");
        System.out.println("  User A: " + userIdA + " → 락 순서: product" + productId2 + " → product" + productId1);
        System.out.println("  User B: " + userIdB + " → 락 순서: product" + productId1 + " → product" + productId2);
        System.out.println("  상품1 ID: " + productId1);
        System.out.println("  상품2 ID: " + productId2);
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown() {
        System.out.println("[정리 시작]");

        for (Long uid : List.of(userIdA, userIdB)) {
            // 쿠폰 FK 해제 후 주문 삭제
            cleanUpOrdersForUser(uid);

            // 장바구니 정리
            jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", uid);
        }

        // 상품 원본 상태 복원
        for (Map.Entry<Long, Map<String, Object>> entry : originalProductStates.entrySet()) {
            Long pid = entry.getKey();
            Map<String, Object> state = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                    state.get("stock_quantity"), state.get("sales_count"), pid);
        }

        // 사용자 원본 상태 복원
        for (Map.Entry<Long, Map<String, Object>> entry : originalUserStates.entrySet()) {
            Long uid = entry.getKey();
            Map<String, Object> state = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                    state.get("total_spent"), state.get("point_balance"), state.get("tier_id"), uid);
        }

        System.out.println("[정리 완료]");
    }

    private void cleanUpOrdersForUser(Long userId) {
        jdbcTemplate.update(
                """
                UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL
                WHERE order_id IN (
                    SELECT order_id FROM orders
                    WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'
                )
                """,
                userId);
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE created_by = ? AND created_at > NOW() - INTERVAL '10 minutes'",
                userId);
        jdbcTemplate.update(
                "DELETE FROM orders WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'",
                userId);
    }

    /**
     * 2명이 같은 2개 상품을 역순 락으로 동시 주문 → 데드락 발생 여부 검증
     *
     * 가능한 결과:
     * (a) 데드락 발생 → PostgreSQL이 하나를 롤백 → 1건 성공, 1건 실패
     * (b) 타이밍상 데드락 미발생 → 2건 모두 성공
     *
     * 이 테스트를 10회 반복하여 데드락 발생 확률을 측정합니다.
     * 1회라도 발생하면 코드 수정이 필요합니다.
     */
    @RepeatedTest(value = 10, name = "데드락 테스트 {currentRepetition}/{totalRepetitions}")
    @DisplayName("역순 락 주문 → 데드락 발생 여부 감지")
    void deadlock_detection(RepetitionInfo repetitionInfo) throws InterruptedException {
        // Given
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger deadlockCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 테스트구 데드락로 999",
                "데드락테스트",
                "010-0000-0000",
                "CARD",
                BigDecimal.ZERO,
                null, null
        );

        // When: 2명 동시 주문
        for (Long userId : List.of(userIdA, userIdB)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.createOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    // PostgreSQL 데드락: "ERROR: deadlock detected"
                    // 또는 Spring이 감싸는 CannotAcquireLockException
                    if (msg.contains("deadlock") || msg.contains("could not serialize")
                            || e.getClass().getSimpleName().contains("CannotAcquireLock")
                            || e.getClass().getSimpleName().contains("PessimisticLocking")) {
                        deadlockCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("userId=" + userId + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
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

        // 결과 수집
        int round = repetitionInfo.getCurrentRepetition();
        System.out.printf("[Round %2d] 성공: %d, 데드락: %d, 기타실패: %d%n",
                round, successCount.get(), deadlockCount.get(), otherFailCount.get());
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  → " + e));
        }

        // 정리 (다음 반복을 위해)
        for (Long uid : List.of(userIdA, userIdB)) {
            cleanUpOrdersForUser(uid);
        }

        // 장바구니 재설정 (createOrder가 성공하면 장바구니를 삭제하므로)
        LocalDateTime earlier = LocalDateTime.now().minusSeconds(10);
        LocalDateTime later = LocalDateTime.now();
        for (Long uid : List.of(userIdA, userIdB)) {
            jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", uid);
        }
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdA, productId1, earlier, earlier);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdA, productId2, later, later);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, productId2, earlier, earlier);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, productId1, later, later);

        // 사용자 원본 상태 복원 (포인트/등급 변경 원복)
        for (Map.Entry<Long, Map<String, Object>> entry : originalUserStates.entrySet()) {
            Long uid = entry.getKey();
            Map<String, Object> state = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                    state.get("total_spent"), state.get("point_balance"), state.get("tier_id"), uid);
        }

        // 상품 원본 상태 복원
        for (Map.Entry<Long, Map<String, Object>> entry : originalProductStates.entrySet()) {
            Long pid = entry.getKey();
            Map<String, Object> state = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                    state.get("stock_quantity"), state.get("sales_count"), pid);
        }

        // Then: 데드락이 발생하면 안 됨
        assertThat(deadlockCount.get())
                .as("[Round %d] 데드락이 발생했습니다! 락 순서를 product_id 기준으로 정렬해야 합니다.", round)
                .isEqualTo(0);

        // 기타 예외도 없어야 함
        assertThat(otherFailCount.get())
                .as("[Round %d] 예상치 못한 예외: %s", round, errors)
                .isEqualTo(0);

        // 2명 모두 성공해야 함
        assertThat(successCount.get())
                .as("[Round %d] 2명 모두 주문에 성공해야 합니다", round)
                .isEqualTo(2);
    }
}
