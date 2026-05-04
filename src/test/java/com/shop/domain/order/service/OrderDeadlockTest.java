package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderDeadlockTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private Long userIdA;
    private Long userIdB;
    private Long productId1;
    private Long productId2;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();

        userIdA = fixture.createActiveUser();
        userIdB = fixture.createActiveUser();
        productId1 = fixture.createActiveProduct(100);
        productId2 = fixture.createActiveProduct(100);
    }

    @AfterEach
    void tearDown() {
        for (Long uid : List.of(userIdA, userIdB)) {
            jdbcTemplate.update("DELETE FROM point_history WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM product_inventory_history WHERE created_by = ?", uid);
            jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", uid);
        }
        fixture.cleanup();
    }

    /**
     * 역순 락 장바구니를 설정하는 헬퍼.
     *
     * User A: product1(earlier) → product2(later)
     * → 조회 시 ORDER BY updatedAt DESC → [product2, product1] 순서로 락
     *
     * User B: product2(earlier) → product1(later)
     * → 조회 시 ORDER BY updatedAt DESC → [product1, product2] 순서로 락
     */
    private void setUpReverseLockCarts() {
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
    }

    private void cleanUpRoundData() {
        for (Long uid : List.of(userIdA, userIdB)) {
            jdbcTemplate.update("DELETE FROM point_history WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM product_inventory_history WHERE created_by = ?", uid);
            jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", uid);
        }
        // 상품 재고 원복
        jdbcTemplate.update("UPDATE products SET stock_quantity = 100, sales_count = 0 WHERE product_id = ?", productId1);
        jdbcTemplate.update("UPDATE products SET stock_quantity = 100, sales_count = 0 WHERE product_id = ?", productId2);
        // 사용자 집계 원복
        for (Long uid : List.of(userIdA, userIdB)) {
            jdbcTemplate.update("UPDATE users SET total_spent = 0, point_balance = 0 WHERE user_id = ?", uid);
        }
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
    @SuppressWarnings("PMD.CloseResource")
    void deadlock_detection(RepetitionInfo repetitionInfo) throws InterruptedException {
        // 매 반복마다 깨끗한 상태에서 시작
        cleanUpRoundData();
        setUpReverseLockCarts();

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
                null, null, null
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

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        // 결과 수집
        int round = repetitionInfo.getCurrentRepetition();
        System.out.printf("[Round %2d] 성공: %d, 데드락: %d, 기타실패: %d%n",
                round, successCount.get(), deadlockCount.get(), otherFailCount.get());
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  → " + e));
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
