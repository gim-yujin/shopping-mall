package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.domain.user.scheduler.TierScheduler;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.time.Year;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 취소 동시성 테스트
 *
 * 시나리오 1 — 이중 취소 (Double Cancel)
 *   같은 주문에 대해 5개 스레드가 동시에 cancelOrder 호출
 *   위험: Order에 비관적 락이 없으므로 5개 모두 isCancellable()=true를 읽고 진입
 *   → 재고 5번 복구, 포인트 5번 차감 → 데이터 부정합
 *
 * 시나리오 2 — 취소 + 생성 경합 (Cancel vs Create)
 *   같은 상품에 대해 한 스레드는 주문 취소(재고 복구), 다른 스레드는 주문 생성(재고 차감)
 *   → 최종 재고가 정확해야 함
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class CancelOrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TierScheduler tierScheduler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTierRepository userTierRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;

    // 테스트 대상
    private Long testUserId;
    private Long testProductId;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        testUserId = fixture.createActiveUser();
        testProductId = fixture.createActiveProduct(100);
    }

    @AfterEach
    void tearDown() {
        cleanUpOrdersForUser(testUserId);
        fixture.cleanup();
    }

    private void cleanUpOrdersForUser(Long userId) {
        jdbcTemplate.update(
                "DELETE FROM point_history WHERE user_id = ?", userId);
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE created_by = ?", userId);
        jdbcTemplate.update(
                "DELETE FROM orders WHERE user_id = ?", userId);
    }

    /**
     * 주문 1건을 생성하고 orderId를 반환하는 헬퍼
     */
    private Long createTestOrder() {
        // 장바구니에 상품 추가
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                testUserId, testProductId, now, now);

        // 주문 생성
        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 테스트구 취소테스트로 1",
                "취소테스트수령인",
                "010-1111-2222",
                "CARD",
                BigDecimal.ZERO,
                null, null, null
        );
        Order order = orderService.createOrder(testUserId, request);
        return order.getOrderId();
    }

    // =========================================================================
    // 시나리오 1: 이중 취소 (Double Cancel)
    // =========================================================================

    /**
     * 같은 주문에 대해 5개 스레드가 동시에 취소 요청
     *
     * 현재 코드의 문제:
     *   getOrderDetail() — Order에 비관적 락 없음
     *   isCancellable() — 5개 스레드 모두 "PAID" 상태를 읽음
     *   → 전부 진입하여 재고 5번 복구, 포인트 5번 차감
     *
     * 기대 결과:
     *   정확히 1건만 성공, 나머지는 실패
     *   재고 = 원본값 (주문 시 1 감소 → 취소 시 1 복구 = 원본)
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("시나리오 1: 같은 주문 5회 동시 취소 → 1회만 성공, 재고 정확히 1번만 복구")
    void doubleCancel_prevention() throws InterruptedException {
        // Given: 주문 1건 생성
        Long orderId = createTestOrder();

        // 주문 생성 직후 상태 기록 (취소 후 원본으로 돌아가야 함)
        int stockAfterOrder = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesAfterOrder = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // fixture가 생성한 상품의 초기 상태
        int originalStock = 100;
        int originalSalesCount = 0;

        System.out.println("========================================");
        System.out.println("[이중 취소 테스트]");
        System.out.println("  주문 ID: " + orderId);
        System.out.println("  원본 재고: " + originalStock);
        System.out.println("  주문 후 재고: " + stockAfterOrder);
        System.out.println("========================================");

        // When: 5개 스레드가 동시에 같은 주문 취소
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cancelFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.cancelOrder(orderId, testUserId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("취소할 수 없는")) {
                        cancelFailCount.incrementAndGet();
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
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        // Then: DB 직접 조회
        Integer finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        Integer finalSalesCount = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        String orderStatus = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, orderId);

        // 재고 복구 횟수 = 최종 재고 - 주문 후 재고
        int restoredCount = finalStock - stockAfterOrder;

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  취소 성공:        " + successCount.get() + "건");
        System.out.println("  이미 취소됨:      " + cancelFailCount.get() + "건");
        System.out.println("  기타 실패:        " + otherFailCount.get() + "건");
        System.out.println("  ─────────────────────────────");
        System.out.println("  원본 재고:        " + originalStock);
        System.out.println("  주문 후 재고:     " + stockAfterOrder);
        System.out.println("  취소 후 최종 재고: " + finalStock);
        System.out.println("  주문 후 판매량:     " + salesAfterOrder);
        System.out.println("  취소 후 최종 판매량: " + finalSalesCount);
        System.out.println("  재고 복구 횟수:    " + restoredCount + "회 (기대: 1회)");
        System.out.println("  주문 상태:         " + orderStatus);
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 재고는 원본값으로 정확히 복구 (1번만 복구되어야 함)
        assertThat(finalStock)
                .as("재고는 원본값(%d)으로 정확히 복구되어야 합니다 (현재: %d, 초과 복구: %d회)",
                        originalStock, finalStock, restoredCount - 1)
                .isEqualTo(originalStock);

        // ② 재고 복구는 정확히 1번
        assertThat(restoredCount)
                .as("재고는 정확히 1번만 복구되어야 합니다")
                .isEqualTo(1);

        // ③ 판매량은 원본으로 정확히 복구
        assertThat(finalSalesCount)
                .as("판매량은 원본값(%d)으로 정확히 복구되어야 합니다", originalSalesCount)
                .isEqualTo(originalSalesCount);

        // ④ 주문 상태는 CANCELLED
        assertThat(orderStatus)
                .as("주문 상태는 CANCELLED여야 합니다")
                .isEqualTo("CANCELLED");

        // ⑤ 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외가 발생하면 안 됩니다: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 2: 취소 + 생성 경합 (Cancel vs Create)
    // =========================================================================

    /**
     * 같은 상품에 대해:
     *   Thread A: 기존 주문 취소 (재고 +1 복구)
     *   Thread B: 새 주문 생성 (재고 -1 차감)
     *   Thread C: 등급 재산정 스케줄러
     *
     * Cancel(Order→Product→User)과 Create(User→Product)의 락 획득 순서가 다르므로
     * 데드락이 발생할 수 있다. PostgreSQL이 데드락을 감지하면 한 트랜잭션을 롤백한다.
     *
     * 검증 목표: 성공한 작업들에 대해 재고/판매량/사용자 집계 불변식이 유지되는지 확인
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("시나리오 2: 취소 + 생성 + 등급 갱신 동시 실행 → 데이터 정합성 유지")
    void cancelAndCreate_stockConsistency() throws InterruptedException {
        // Given: 주문 A 생성 (재고 1 소비됨)
        Long orderIdA = createTestOrder();

        int stockAfterOrderA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesAfterOrderA = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // User B 준비 (다른 사용자가 같은 상품 주문) — fixture로 격리된 사용자 생성
        Long userIdB = fixture.createActiveUser();

        // User B 장바구니에 같은 상품 추가
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, testProductId, now, now);

        System.out.println("========================================");
        System.out.println("[취소 + 생성 경합 테스트]");
        System.out.println("  User A (취소): " + testUserId + " → 주문 " + orderIdA + " 취소 (재고 +1)");
        System.out.println("  User B (생성): " + userIdB + " → 새 주문 생성 (재고 -1)");
        System.out.println("  주문A 후 재고: " + stockAfterOrderA);
        System.out.println("========================================");

        // When: 동시 실행 (취소 + 생성 + 등급 갱신)
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        AtomicInteger cancelSuccess = new AtomicInteger(0);
        AtomicInteger createSuccess = new AtomicInteger(0);
        AtomicInteger tierRecalcSuccess = new AtomicInteger(0);
        AtomicReference<Long> createdOrderIdByUserB = new AtomicReference<>();
        List<String> deadlockErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        OrderCreateRequest requestB = new OrderCreateRequest(
                "서울시 테스트구 경합테스트로 2",
                "경합테스트수령인",
                "010-3333-4444",
                "CARD",
                BigDecimal.ZERO,
                null, null, null
        );

        // Thread A: 주문 취소
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.cancelOrder(orderIdA, testUserId);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                categorizeError("[Cancel]", e, deadlockErrors, unexpectedErrors);
            } finally {
                done.countDown();
            }
        });

        // Thread B: 주문 생성
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                Order createdOrder = orderService.createOrder(userIdB, requestB);
                createdOrderIdByUserB.set(createdOrder.getOrderId());
                createSuccess.incrementAndGet();
            } catch (Exception e) {
                categorizeError("[Create]", e, deadlockErrors, unexpectedErrors);
            } finally {
                done.countDown();
            }
        });

        // Thread C: 등급 재산정 스케줄러 실행
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                runTierChunkForUsers(List.of(testUserId));
                tierRecalcSuccess.incrementAndGet();
            } catch (Exception e) {
                categorizeError("[TierScheduler]", e, deadlockErrors, unexpectedErrors);
            } finally {
                done.countDown();
            }
        });

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        // Then
        Integer finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        Integer finalSalesCount = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  취소 성공: " + cancelSuccess.get());
        System.out.println("  생성 성공: " + createSuccess.get());
        System.out.println("  등급 갱신 성공: " + tierRecalcSuccess.get());
        System.out.println("  최종 재고: " + finalStock);
        System.out.println("  최종 판매량: " + finalSalesCount);
        if (!deadlockErrors.isEmpty()) {
            System.out.println("  데드락 (예상 범위):");
            deadlockErrors.forEach(e -> System.out.println("    → " + e));
        }
        if (!unexpectedErrors.isEmpty()) {
            System.out.println("  예상치 못한 에러:");
            unexpectedErrors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 취소와 생성 중 적어도 하나는 성공해야 함
        //    (데드락 시 PostgreSQL이 하나만 롤백하므로 동시에 둘 다 실패하지 않음)
        assertThat(cancelSuccess.get() + createSuccess.get())
                .as("취소와 생성 중 적어도 하나는 성공해야 합니다 (데드락: %s)", deadlockErrors)
                .isGreaterThanOrEqualTo(1);

        // ② 재고 불변식: final = afterOrderA + cancelSuccess - createSuccess
        int expectedStock = stockAfterOrderA + cancelSuccess.get() - createSuccess.get();
        assertThat(finalStock)
                .as("재고 불변식: afterOrderA(%d) + cancel(%d) - create(%d) = %d",
                        stockAfterOrderA, cancelSuccess.get(), createSuccess.get(), expectedStock)
                .isEqualTo(expectedStock);

        // ③ 판매량 불변식: final = afterOrderA - cancelSuccess + createSuccess
        int expectedSales = salesAfterOrderA - cancelSuccess.get() + createSuccess.get();
        assertThat(finalSalesCount)
                .as("판매량 불변식: afterOrderA(%d) - cancel(%d) + create(%d) = %d",
                        salesAfterOrderA, cancelSuccess.get(), createSuccess.get(), expectedSales)
                .isEqualTo(expectedSales);

        // ④ 사용자 집계 불변식 (성공한 작업에 대해서만 검증)
        BigDecimal userATotalSpent = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?", BigDecimal.class, testUserId);
        Integer userAPointBalance = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?", Integer.class, testUserId);

        assertThat(userATotalSpent).as("User A total_spent는 음수가 될 수 없습니다")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(userAPointBalance).as("User A point_balance는 음수가 될 수 없습니다")
                .isGreaterThanOrEqualTo(0);

        if (cancelSuccess.get() == 1) {
            // 취소 성공 시 User A의 total_spent, point_balance는 원복 (fixture 초기값 = 0)
            assertThat(userATotalSpent).as("User A 취소 후 total_spent는 원복되어야 합니다")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(userAPointBalance).as("User A 취소 후 point_balance는 원복되어야 합니다")
                    .isEqualTo(0);
        }

        if (createSuccess.get() == 1) {
            Long orderIdB = createdOrderIdByUserB.get();
            assertThat(orderIdB).as("User B 생성 주문 ID는 기록되어야 합니다").isNotNull();

            BigDecimal orderBFinalAmount = jdbcTemplate.queryForObject(
                    "SELECT final_amount FROM orders WHERE order_id = ?",
                    BigDecimal.class, orderIdB);

            BigDecimal userBTotalSpent = jdbcTemplate.queryForObject(
                    "SELECT total_spent FROM users WHERE user_id = ?", BigDecimal.class, userIdB);
            Integer userBPointBalance = jdbcTemplate.queryForObject(
                    "SELECT point_balance FROM users WHERE user_id = ?", Integer.class, userIdB);

            assertThat(userBTotalSpent).as("User B total_spent는 음수가 될 수 없습니다")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(userBPointBalance).as("User B point_balance는 음수가 될 수 없습니다")
                    .isGreaterThanOrEqualTo(0);
            assertThat(userBTotalSpent).as("User B total_spent는 주문 금액만큼 증가해야 합니다")
                    .isEqualByComparingTo(orderBFinalAmount);
            // 포인트 적립은 배송 완료(DELIVERED) 시점에 정산되므로 createOrder 직후에는 0
            assertThat(userBPointBalance).as("User B point_balance는 주문 직후 0이어야 합니다")
                    .isEqualTo(0);
        }

        // ⑤ 예상치 못한 에러 없음 (데드락은 예상 범위)
        assertThat(unexpectedErrors)
                .as("예상치 못한 에러가 없어야 합니다")
                .isEmpty();

        // User B 주문 데이터 정리 (fixture.cleanup()이 사용자/상품/장바구니는 처리)
        cleanUpOrdersForUser(userIdB);
    }
    private static boolean isDeadlockException(Throwable e) {
        while (e != null) {
            String name = e.getClass().getSimpleName();
            String msg = e.getMessage();
            if (name.contains("PessimisticLocking") || name.contains("CannotAcquireLock")
                    || name.contains("LockAcquisition")
                    || (msg != null && msg.toLowerCase(Locale.ROOT).contains("deadlock"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private static void categorizeError(String prefix, Exception e,
                                         List<String> deadlockErrors, List<String> unexpectedErrors) {
        String detail = prefix + " " + e.getClass().getSimpleName() + " - " + e.getMessage();
        if (isDeadlockException(e)) {
            deadlockErrors.add(detail);
        } else {
            unexpectedErrors.add(detail);
        }
    }

    private void runTierChunkForUsers(List<Long> userIds) {
        try {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    List<User> users = userIds.stream()
                            .map(userId -> userRepository.findByIdWithTier(userId)
                                    .orElseThrow(() -> new IllegalStateException("등급 갱신 대상 사용자가 없습니다. userId=" + userId)))
                            .toList();
                    UserTier defaultTier = userTierRepository.findByTierLevel(1)
                            .orElseThrow(() -> new IllegalStateException("기본 등급이 존재하지 않습니다."));
                    // [Phase 20] processTierChunk 시그니처 변경: allTiersBySpentDesc 파라미터 추가
                    List<UserTier> allTiersBySpentDesc = userTierRepository.findAllByOrderByMinSpentDesc();

                    Method processTierChunk = TierScheduler.class.getDeclaredMethod(
                            "processTierChunk", int.class, Map.class, UserTier.class, List.class, List.class);
                    processTierChunk.setAccessible(true);
                    processTierChunk.invoke(tierScheduler, Year.now().getValue() - 1, Map.of(),
                            defaultTier, allTiersBySpentDesc, users);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("테스트용 등급 갱신 실행 실패", e);
        }
    }

}
