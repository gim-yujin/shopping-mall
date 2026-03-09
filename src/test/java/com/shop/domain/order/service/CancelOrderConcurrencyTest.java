package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.domain.user.scheduler.TierScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        "spring.datasource.hikari.maximum-pool-size=50",
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

    // 테스트 대상
    private Long testUserId;
    private Long testProductId;

    // 원본 상태 백업
    private int originalStock;
    private int originalSalesCount;
    private Map<String, Object> originalUserState;

    @BeforeEach
    void setUp() {
        // 장바구니 비어있는 사용자 선택
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        originalUserState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserId);

        // 이전 실행 잔여 데이터 정리
        cleanUpOrdersForUser(testUserId);

        // 재고 충분한 상품 선택
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        Map<String, Object> productState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);
        originalStock = ((Number) productState.get("stock_quantity")).intValue();
        originalSalesCount = ((Number) productState.get("sales_count")).intValue();
    }

    @AfterEach
    void tearDown() {
        System.out.println("[정리 시작]");

        cleanUpOrdersForUser(testUserId);

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
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            done.await(60, TimeUnit.SECONDS);
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
     *   → 동시 실행 → 최종 재고 = 원본값 (복구와 차감이 상쇄)
     *
     * 두 작업 모두 PESSIMISTIC_WRITE + refresh를 사용하므로
     * 직렬화되어 정확한 결과가 나와야 합니다.
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("시나리오 2: 취소 + 생성 + 등급 갱신 동시 실행 → 사용자 집계/재고 불변식 유지")
    void cancelAndCreate_stockConsistency() throws InterruptedException {
        // Given: 주문 A 생성 (재고 1 소비됨)
        Long orderIdA = createTestOrder();

        int stockAfterOrderA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesAfterOrderA = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // User B 준비 (다른 사용자가 같은 상품 주문)
        Long userIdB = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND u.user_id != ?
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class, testUserId);

        Map<String, Object> userBState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                userIdB);

        // User B 장바구니에 같은 상품 추가
        String now = LocalDateTime.now().toString();
        cleanUpOrdersForUser(userIdB);
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", userIdB);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                userIdB, testProductId, now, now);

        System.out.println("========================================");
        System.out.println("[취소 + 생성 경합 테스트]");
        System.out.println("  User A (취소): " + testUserId + " → 주문 " + orderIdA + " 취소 (재고 +1)");
        System.out.println("  User B (생성): " + userIdB + " → 새 주문 생성 (재고 -1)");
        System.out.println("  원본 재고: " + originalStock);
        System.out.println("  주문A 후 재고: " + stockAfterOrderA);
        System.out.println("  기대 최종 재고: " + stockAfterOrderA + " (취소 +1과 생성 -1 상쇄 → 주문A 후 재고와 동일)");
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
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

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
                errors.add("[Cancel] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread B: 주문 생성
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                int maxAttempts = 3;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        Order createdOrder = orderService.createOrder(userIdB, requestB);
                        createdOrderIdByUserB.set(createdOrder.getOrderId());
                        createSuccess.incrementAndGet();
                        break;
                    } catch (PessimisticLockingFailureException e) {
                        if (attempt == maxAttempts) {
                            throw e;
                        }
                        Thread.sleep(100L * attempt);
                    }
                }
            } catch (Exception e) {
                errors.add("[Create] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread C: 등급 재산정 스케줄러 실행
        // 주문 취소/생성과 동시에 실행되면 비관적 락 경합이 발생할 수 있다.
        // Thread B와 동일한 재시도 패턴을 적용하여 CI 환경에서의 타이밍 이슈를 방지한다.
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                int maxAttempts = 3;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        runTierChunkForUsers(List.of(testUserId));
                        tierRecalcSuccess.incrementAndGet();
                        break;
                    } catch (Exception e) {
                        if (attempt == maxAttempts) {
                            throw e;
                        }
                        Thread.sleep(200L * attempt);
                    }
                }
            } catch (Exception e) {
                errors.add("[TierScheduler] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        try {
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            done.await(60, TimeUnit.SECONDS);
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
        System.out.println("  최종 재고: " + finalStock + " (기대: " + stockAfterOrderA + ")");
        System.out.println("  최종 판매량: " + finalSalesCount + " (기대: " + salesAfterOrderA + ")");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        BigDecimal userATotalSpent = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?", BigDecimal.class, testUserId);
        Integer userAPointBalance = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?", Integer.class, testUserId);

        Long orderIdB = createdOrderIdByUserB.get();
        assertThat(orderIdB).as("User B 생성 주문 ID는 기록되어야 합니다").isNotNull();

        BigDecimal orderBFinalAmount = jdbcTemplate.queryForObject(
                "SELECT final_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, orderIdB);
        Integer orderBEarnedPoints = jdbcTemplate.queryForObject(
                "SELECT earned_points_snapshot FROM orders WHERE order_id = ?",
                Integer.class, orderIdB);

        BigDecimal userBTotalSpent = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?", BigDecimal.class, userIdB);
        Integer userBPointBalance = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?", Integer.class, userIdB);

        BigDecimal expectedUserATotalSpent = (BigDecimal) originalUserState.get("total_spent");
        Integer expectedUserAPointBalance = ((Number) originalUserState.get("point_balance")).intValue();
        BigDecimal expectedUserBTotalSpent = ((BigDecimal) userBState.get("total_spent")).add(orderBFinalAmount);
        // 포인트 적립은 배송 완료(DELIVERED) 시점에 정산되므로,
        // createOrder 직후에는 point_balance가 증가하지 않는다.
        Integer expectedUserBPointBalance = ((Number) userBState.get("point_balance")).intValue();

        // ① 양쪽 모두 성공 + 등급 갱신 성공
        assertThat(cancelSuccess.get())
                .as("취소가 성공해야 합니다")
                .isEqualTo(1);
        assertThat(createSuccess.get())
                .as("생성이 성공해야 합니다")
                .isEqualTo(1);
        assertThat(tierRecalcSuccess.get())
                .as("등급 갱신 작업이 성공해야 합니다")
                .isEqualTo(1);

        // ② 사용자 집계 불변식: 음수 불가
        assertThat(userATotalSpent).as("User A total_spent는 음수가 될 수 없습니다").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(userBTotalSpent).as("User B total_spent는 음수가 될 수 없습니다").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(userAPointBalance).as("User A point_balance는 음수가 될 수 없습니다").isGreaterThanOrEqualTo(0);
        assertThat(userBPointBalance).as("User B point_balance는 음수가 될 수 없습니다").isGreaterThanOrEqualTo(0);

        // ③ 사용자 집계 계산 일치 검증
        assertThat(userATotalSpent).as("User A 취소 후 total_spent는 원복되어야 합니다").isEqualByComparingTo(expectedUserATotalSpent);
        assertThat(userAPointBalance).as("User A 취소 후 point_balance는 원복되어야 합니다").isEqualTo(expectedUserAPointBalance);
        assertThat(userBTotalSpent).as("User B total_spent는 생성 주문 금액만큼 증가해야 합니다").isEqualByComparingTo(expectedUserBTotalSpent);
        assertThat(userBPointBalance).as("User B point_balance는 생성 주문 적립 포인트만큼 증가해야 합니다").isEqualTo(expectedUserBPointBalance);

        // ② 최종 재고 = 주문A 후 재고 (취소 +1, 생성 -1 = 상쇄 → 변화 없음)
        assertThat(finalStock)
                .as("취소(+1)와 생성(-1)이 상쇄되어 주문A 후 재고(%d)와 같아야 합니다", stockAfterOrderA)
                .isEqualTo(stockAfterOrderA);

        // ③ 최종 판매량 = 주문A 후 판매량 (취소 -1, 생성 +1 = 상쇄 → 변화 없음)
        assertThat(finalSalesCount)
                .as("취소(-1)와 생성(+1)이 상쇄되어 주문A 후 판매량(%d)과 같아야 합니다", salesAfterOrderA)
                .isEqualTo(salesAfterOrderA);

        // ④ 예상치 못한 에러 없음
        assertThat(errors)
                .as("예상치 못한 에러가 없어야 합니다")
                .isEmpty();

        // 정리: User B 데이터 복원
        cleanUpOrdersForUser(userIdB);
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", userIdB);
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                userBState.get("total_spent"), userBState.get("point_balance"), userBState.get("tier_id"), userIdB);
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

                    Method processTierChunk = TierScheduler.class.getDeclaredMethod(
                            "processTierChunk", int.class, Map.class, UserTier.class, List.class);
                    processTierChunk.setAccessible(true);
                    processTierChunk.invoke(tierScheduler, Year.now().getValue() - 1, Map.of(), defaultTier, users);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("테스트용 등급 갱신 실행 실패", e);
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
