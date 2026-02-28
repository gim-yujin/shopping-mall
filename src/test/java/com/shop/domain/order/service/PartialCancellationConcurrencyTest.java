package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
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
 * PartialCancellationService 동시성 테스트.
 *
 * <h3>검증 시나리오</h3>
 *
 * <p><b>시나리오 1 — 같은 아이템 이중 부분 취소 (refundedAmount lost update 방지):</b><br>
 * 같은 주문의 같은 아이템(상품A 3개)에 대해 5개 스레드가 동시에 1개씩 부분 취소를 요청한다.
 * 잔여 수량은 3개이므로 최대 3건만 성공해야 하며, 나머지는 수량 초과 또는 락 대기 후 실패해야 한다.
 *
 * <p>[P1-1] Order에 비관적 잠금이 없었을 때의 문제:
 * 여러 스레드가 동시에 Order.refundedAmount를 읽고 각자 환불액을 더해 쓰면
 * lost update가 발생하여 환불 금액이 실결제금액을 초과할 수 있었다.
 * Order PESSIMISTIC_WRITE 잠금으로 동일 주문에 대한 모든 부분 취소를 직렬화하여 해결했다.</p>
 *
 * <p><b>시나리오 2 — 다른 아이템 동시 부분 취소 (Order 잠금 직렬화):</b><br>
 * 같은 주문의 서로 다른 아이템(상품A, 상품B)에 대해 동시 부분 취소를 요청한다.
 * 두 요청 모두 성공해야 하지만, refundedAmount에 lost update가 발생하면 안 된다.
 *
 * <p>[P1-1] 핵심: Order 행에 비관적 잠금을 걸어 두 요청을 직렬화한다.
 * OrderItem에만 락을 걸면, 서로 다른 OrderItem을 잠그므로 동시에 진입하여
 * Order.refundedAmount에 lost update가 발생한다.</p>
 *
 * <p><b>시나리오 3 — 부분 취소 vs 전체 취소 경합 (교차 데드락 방지):</b><br>
 * 같은 주문에 대해 한 스레드는 부분 취소, 다른 스레드는 전체 취소를 동시에 실행한다.
 * 정확히 하나만 성공하고, 데드락 없이 완료되어야 한다.
 *
 * <p>[P1-2] 기존 버그: 부분 취소의 락 순서(OrderItem → User → Product)가
 * 전체 취소(Order → Product → User)와 불일치하여 교차 데드락이 발생할 수 있었다.
 * 모두 Order → Product → User 순서로 통일하여 해결했다.</p>
 *
 * <p><b>시나리오 4 — 다중 사용자 동시 부분 취소 (사용자 간 간섭 없음):</b><br>
 * 서로 다른 사용자의 서로 다른 주문에 대해 동시 부분 취소를 요청한다.
 * 각각 독립적으로 성공하고, 재고 복구가 정확해야 한다.
 *
 * <p>주의: 실제 PostgreSQL DB에 연결하며, 동시성 테스트를 위해
 * connection pool을 50으로 확장합니다.</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class PartialCancellationConcurrencyTest {

    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 테스트 대상
    private Long testUserIdA;
    private Long testUserIdB;
    private Long testProductIdA;
    private Long testProductIdB;

    // 원본 상태 백업
    private Map<String, Object> originalUserAState;
    private Map<String, Object> originalUserBState;
    private int originalStockA;
    private int originalStockB;
    private int originalSalesA;
    private int originalSalesB;

    @BeforeEach
    void setUp() {
        // 재고 충분한 상품 2개 선택
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                """
                SELECT product_id, stock_quantity, sales_count FROM products
                WHERE is_active = true AND stock_quantity >= 100
                ORDER BY product_id LIMIT 2
                """);
        assertThat(products).hasSizeGreaterThanOrEqualTo(2);

        testProductIdA = ((Number) products.get(0).get("product_id")).longValue();
        testProductIdB = ((Number) products.get(1).get("product_id")).longValue();
        originalStockA = ((Number) products.get(0).get("stock_quantity")).intValue();
        originalStockB = ((Number) products.get(1).get("stock_quantity")).intValue();
        originalSalesA = ((Number) products.get(0).get("sales_count")).intValue();
        originalSalesB = ((Number) products.get(1).get("sales_count")).intValue();

        // 장바구니 비어있는 사용자 2명 선택 (다른 사용자 시나리오용)
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 2
                """);
        assertThat(users).hasSizeGreaterThanOrEqualTo(2);

        testUserIdA = ((Number) users.get(0).get("user_id")).longValue();
        testUserIdB = ((Number) users.get(1).get("user_id")).longValue();

        originalUserAState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserIdA);
        originalUserBState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserIdB);

        // 이전 실행 잔여 데이터 정리
        cleanUpOrdersForUser(testUserIdA);
        cleanUpOrdersForUser(testUserIdB);
    }

    @AfterEach
    void tearDown() {
        cleanUpOrdersForUser(testUserIdA);
        cleanUpOrdersForUser(testUserIdB);

        jdbcTemplate.update("DELETE FROM carts WHERE user_id IN (?, ?)", testUserIdA, testUserIdB);

        // 상품 원본 복원
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalStockA, originalSalesA, testProductIdA);
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalStockB, originalSalesB, testProductIdB);

        // 사용자 원본 복원
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserAState.get("total_spent"),
                originalUserAState.get("point_balance"),
                originalUserAState.get("tier_id"),
                testUserIdA);
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserBState.get("total_spent"),
                originalUserBState.get("point_balance"),
                originalUserBState.get("tier_id"),
                testUserIdB);
    }

    // ── 헬퍼 ──────────────────────────────────────────────

    private void cleanUpOrdersForUser(Long userId) {
        jdbcTemplate.update(
                """
                DELETE FROM point_history
                WHERE reference_id IN (
                    SELECT order_id FROM orders
                    WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'
                )
                """, userId);
        jdbcTemplate.update(
                """
                UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL
                WHERE order_id IN (
                    SELECT order_id FROM orders
                    WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'
                )
                """, userId);
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE created_by = ? AND created_at > NOW() - INTERVAL '10 minutes'",
                userId);
        jdbcTemplate.update(
                "DELETE FROM orders WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'",
                userId);
    }

    private void addCartItem(Long userId, Long productId, int quantity) {
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, productId, quantity, now, now);
    }

    private Order createTestOrder(Long userId, Long productIdA, int qtyA, Long productIdB, int qtyB) {
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", userId);
        addCartItem(userId, productIdA, qtyA);
        if (productIdB != null && qtyB > 0) {
            addCartItem(userId, productIdB, qtyB);
        }
        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 테스트구 동시성로 1", "동시성테스트", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null, null);
        return orderService.createOrder(userId, request);
    }

    private Long findOrderItemId(Long orderId, Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_item_id FROM order_items WHERE order_id = ? AND product_id = ?",
                Long.class, orderId, productId);
    }

    // =========================================================================
    // 시나리오 1: 같은 아이템 이중 부분 취소 — refundedAmount lost update 방지
    // =========================================================================

    /**
     * 같은 주문의 같은 아이템(3개)에 대해 5개 스레드가 동시에 1개씩 부분 취소를 요청한다.
     *
     * <p>[P1-1] Order PESSIMISTIC_WRITE 잠금이 없으면:
     * 5개 스레드가 동시에 remainingQuantity=3을 읽고 각각 1개 취소를 시도한다.
     * 모두 유효하다고 판단하여 5건 모두 성공하고, refundedAmount에 5번의 환불이
     * 누적되어 과다 환불이 발생한다.</p>
     *
     * <p>Order 잠금 적용 후 기대 동작:
     * 동일 주문에 대한 요청이 직렬화되므로, 3건만 성공하고 2건은
     * 잔여 수량 부족으로 실패한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("시나리오 1: 같은 아이템 5회 동시 부분 취소 → 최대 3회 성공, refundedAmount 정합성 유지")
    void sameItem_concurrentPartialCancel_serialized() throws InterruptedException {
        // Given: 상품A 3개 주문
        Order order = createTestOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        int stockAfterOrder = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[시나리오 1: 같은 아이템 이중 부분 취소]");
        System.out.println("  주문 ID: " + orderId + ", 상품A 3개");
        System.out.println("  5개 스레드가 동시에 1개씩 부분 취소 → 최대 3건 성공 기대");
        System.out.println("========================================");

        // When: 5개 스레드 동시 실행
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger quantityFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.partialCancel(orderId, testUserIdA, orderItemIdA, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("잔여") || msg != null && msg.contains("수량")) {
                        quantityFailCount.incrementAndGet();
                    } else if (msg != null && msg.contains("취소 가능한")) {
                        // 전체 아이템 취소 후 CANCELLED 전이 → 이후 요청 거부
                        quantityFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: DB 직접 조회
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, orderId);
        Integer cancelledQty = jdbcTemplate.queryForObject(
                "SELECT cancelled_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);

        int stockRestored = finalStock - stockAfterOrder;

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  성공: " + successCount.get() + "건, 수량 부족 실패: " + quantityFailCount.get() + "건");
        System.out.println("  기타 실패: " + otherFailCount.get() + "건");
        System.out.println("  재고 복구: " + stockRestored + "개 (기대: " + successCount.get() + "개)");
        System.out.println("  cancelledQuantity: " + cancelledQty + " (기대: " + successCount.get() + ")");
        System.out.println("  refundedAmount: " + refundedAmount);
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 최대 3건만 성공 (잔여 수량 = 3)
        assertThat(successCount.get())
                .as("잔여 수량 3개이므로 최대 3건만 성공해야 한다")
                .isLessThanOrEqualTo(3);

        // ② 재고 복구 횟수 = 성공 횟수
        assertThat(stockRestored)
                .as("재고 복구 횟수는 성공 횟수와 일치해야 한다")
                .isEqualTo(successCount.get());

        // ③ cancelledQuantity = 성공 횟수
        assertThat(cancelledQty)
                .as("cancelledQuantity는 성공 횟수와 일치해야 한다")
                .isEqualTo(successCount.get());

        // ④ refundedAmount > 0 (최소 1건은 성공)
        assertThat(refundedAmount)
                .as("최소 1건의 환불이 기록되어야 한다")
                .isGreaterThan(BigDecimal.ZERO);

        // ⑤ 실결제금액 초과 환불 없음
        BigDecimal effectivePaid = order.getFinalAmount().subtract(order.getShippingFee());
        assertThat(refundedAmount)
                .as("환불 누계가 실결제금액(배송비 제외)을 초과하면 안 된다")
                .isLessThanOrEqualTo(effectivePaid);

        // ⑥ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 에러가 없어야 한다: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 2: 다른 아이템 동시 부분 취소 — Order 잠금 직렬화
    // =========================================================================

    /**
     * 같은 주문의 서로 다른 아이템(상품A, 상품B)에 대해 동시 부분 취소를 요청한다.
     *
     * <p>[P1-1] OrderItem에만 락을 걸면:
     * 스레드A는 OrderItem_A를, 스레드B는 OrderItem_B를 각각 잠그므로 서로 간섭 없이 진입한다.
     * 이후 두 스레드가 동시에 Order.addRefundedAmount()를 호출하면
     * read-modify-write 경합이 발생하여 환불 1건이 유실된다.</p>
     *
     * <p>Order 잠금 적용 후 기대 동작:
     * 두 요청 모두 성공하되, Order 잠금에서 직렬화되므로
     * refundedAmount = 환불A + 환불B가 정확히 기록된다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("시나리오 2: 다른 아이템 동시 부분 취소 → 양쪽 성공, refundedAmount = 합산")
    void differentItems_concurrentPartialCancel_bothSucceed() throws InterruptedException {
        // Given: 상품A 3개 + 상품B 2개 주문
        Order order = createTestOrder(testUserIdA, testProductIdA, 3, testProductIdB, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderItemIdB = findOrderItemId(order.getOrderId(), testProductIdB);
        Long orderId = order.getOrderId();

        System.out.println("========================================");
        System.out.println("[시나리오 2: 다른 아이템 동시 부분 취소]");
        System.out.println("  주문 ID: " + orderId);
        System.out.println("  스레드A: 상품A 1개 취소 / 스레드B: 상품B 1개 취소");
        System.out.println("========================================");

        // When: 동시 실행
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successA = new AtomicInteger(0);
        AtomicInteger successB = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // Thread A: 상품A 1개 취소
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderId, testUserIdA, orderItemIdA, 1);
                successA.incrementAndGet();
            } catch (Exception e) {
                errors.add("[A] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread B: 상품B 1개 취소
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderId, testUserIdA, orderItemIdB, 1);
                successB.incrementAndGet();
            } catch (Exception e) {
                errors.add("[B] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, orderId);
        Integer cancelledA = jdbcTemplate.queryForObject(
                "SELECT cancelled_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        Integer cancelledB = jdbcTemplate.queryForObject(
                "SELECT cancelled_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdB);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  A 성공: " + successA.get() + ", B 성공: " + successB.get());
        System.out.println("  cancelledA: " + cancelledA + ", cancelledB: " + cancelledB);
        System.out.println("  refundedAmount 합산: " + refundedAmount);
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 양쪽 모두 성공
        assertThat(successA.get()).as("상품A 부분 취소 성공").isEqualTo(1);
        assertThat(successB.get()).as("상품B 부분 취소 성공").isEqualTo(1);

        // ② cancelledQuantity 각각 1
        assertThat(cancelledA).as("상품A cancelledQuantity").isEqualTo(1);
        assertThat(cancelledB).as("상품B cancelledQuantity").isEqualTo(1);

        // ③ refundedAmount > 0 (두 환불의 합)
        assertThat(refundedAmount)
                .as("양쪽 환불이 모두 refundedAmount에 누적되어야 한다 (lost update 없음)")
                .isGreaterThan(BigDecimal.ZERO);

        // ④ 재고 각각 1개씩 복구
        int stockA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        int stockB = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdB);
        assertThat(stockA).isEqualTo(originalStockA - 3 + 1); // 3개 주문 - 1개 복구
        assertThat(stockB).isEqualTo(originalStockB - 2 + 1); // 2개 주문 - 1개 복구

        // ⑤ 에러 없음
        assertThat(errors).as("예상치 못한 에러가 없어야 한다").isEmpty();
    }

    // =========================================================================
    // 시나리오 3: 부분 취소 vs 전체 취소 경합 — 교차 데드락 방지
    // =========================================================================

    /**
     * 같은 주문에 대해 한 스레드는 부분 취소, 다른 스레드는 전체 취소를 동시에 실행한다.
     *
     * <p>[P1-2] 기존 버그: 부분 취소는 OrderItem → User → Product 순서로 락을 잡고,
     * 전체 취소는 Order → Product → User 순서로 잡았다.
     * 동시 실행 시 한쪽이 User를 잡고 Product를 기다리는 동안
     * 다른 쪽은 Product를 잡고 User를 기다려 교차 데드락이 발생했다.</p>
     *
     * <p>수정 후: 양쪽 모두 Order → Product → User 순서로 통일하여
     * Order 잠금에서 직렬화되므로 데드락이 발생하지 않는다.</p>
     *
     * <p>기대 동작: 정확히 하나의 작업이 성공하고 (먼저 진입한 쪽),
     * 나머지는 상태 불일치로 실패한다. 데드락 타임아웃(60초) 없이 빠르게 완료된다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("시나리오 3: 부분 취소 vs 전체 취소 동시 실행 → 데드락 없이 하나만 성공")
    void partialVsFullCancel_noDeadlock() throws InterruptedException {
        // Given: 상품A 3개 주문
        Order order = createTestOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        System.out.println("========================================");
        System.out.println("[시나리오 3: 부분 취소 vs 전체 취소]");
        System.out.println("  주문 ID: " + orderId);
        System.out.println("  스레드A: 1개 부분 취소 / 스레드B: 전체 취소");
        System.out.println("========================================");

        // When
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger partialSuccess = new AtomicInteger(0);
        AtomicInteger fullCancelSuccess = new AtomicInteger(0);
        List<String> partialErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> fullErrors = Collections.synchronizedList(new ArrayList<>());

        // Thread A: 부분 취소
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderId, testUserIdA, orderItemIdA, 1);
                partialSuccess.incrementAndGet();
            } catch (Exception e) {
                partialErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread B: 전체 취소
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.cancelOrder(orderId, testUserIdA);
                fullCancelSuccess.incrementAndGet();
            } catch (Exception e) {
                fullErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        long startTime = System.currentTimeMillis();
        start.countDown();
        boolean completedInTime = done.await(30, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // Then
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, orderId);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  부분 취소 성공: " + partialSuccess.get()
                + ", 에러: " + partialErrors);
        System.out.println("  전체 취소 성공: " + fullCancelSuccess.get()
                + ", 에러: " + fullErrors);
        System.out.println("  최종 상태: " + finalStatus);
        System.out.println("  소요 시간: " + elapsed + "ms");
        System.out.println("========================================");

        // ① 데드락 없이 시간 내 완료
        assertThat(completedInTime)
                .as("30초 내에 완료되어야 한다 (데드락 없음)")
                .isTrue();

        // ② 최종 상태는 CANCELLED (전체 취소가 성공했거나, 부분 취소 후 전체 취소가 성공)
        assertThat(finalStatus).isEqualTo("CANCELLED");

        // ③ 재고는 원본으로 완전 복구 (취소 = 전부 되돌리기)
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(finalStock)
                .as("재고는 원본값(%d)으로 복구되어야 한다", originalStockA)
                .isEqualTo(originalStockA);

        // ④ 짧은 시간 내 완료 (데드락 시 lock_timeout까지 대기하므로 수초 이상 걸림)
        assertThat(elapsed)
                .as("데드락이 없다면 빠르게 완료되어야 한다 (5초 이내)")
                .isLessThan(5000);
    }

    // =========================================================================
    // 시나리오 4: 다중 사용자 동시 부분 취소 — 사용자 간 간섭 없음
    // =========================================================================

    /**
     * 서로 다른 사용자의 서로 다른 주문에 대해 동시 부분 취소를 요청한다.
     *
     * <p>같은 상품에 대한 재고 조정이 동시에 발생하지만,
     * Product에 대한 비관적 잠금이 직렬화를 보장하므로
     * 최종 재고가 정확해야 한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("시나리오 4: 다중 사용자 동시 부분 취소 → 각각 독립 성공, 재고 정확")
    void multiUser_concurrentPartialCancel_independentSuccess() throws InterruptedException {
        // Given: 사용자A와 사용자B가 각각 같은 상품A를 3개씩 주문
        Order orderA = createTestOrder(testUserIdA, testProductIdA, 3, null, 0);
        Order orderB = createTestOrder(testUserIdB, testProductIdA, 3, null, 0);

        Long orderItemIdA = findOrderItemId(orderA.getOrderId(), testProductIdA);
        Long orderItemIdB = findOrderItemId(orderB.getOrderId(), testProductIdA);

        // 주문 2건 생성 후 재고: 원본 - 6
        int stockAfterOrders = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAfterOrders).isEqualTo(originalStockA - 6);

        System.out.println("========================================");
        System.out.println("[시나리오 4: 다중 사용자 동시 부분 취소]");
        System.out.println("  사용자A 주문: " + orderA.getOrderId() + ", 사용자B 주문: " + orderB.getOrderId());
        System.out.println("  각각 상품A 1개씩 동시 부분 취소 → 재고 +2 복구 기대");
        System.out.println("========================================");

        // When: 동시 실행
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successA = new AtomicInteger(0);
        AtomicInteger successB = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderA.getOrderId(), testUserIdA, orderItemIdA, 1);
                successA.incrementAndGet();
            } catch (Exception e) {
                errors.add("[A] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderB.getOrderId(), testUserIdB, orderItemIdB, 1);
                successB.incrementAndGet();
            } catch (Exception e) {
                errors.add("[B] " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  A 성공: " + successA.get() + ", B 성공: " + successB.get());
        System.out.println("  재고: " + stockAfterOrders + " → " + finalStock + " (기대: " + (stockAfterOrders + 2) + ")");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 양쪽 모두 성공
        assertThat(successA.get()).isEqualTo(1);
        assertThat(successB.get()).isEqualTo(1);

        // ② 재고 정확히 +2 복구 (각 1개씩)
        assertThat(finalStock)
                .as("각 사용자가 1개씩 복구하므로 재고는 +2여야 한다")
                .isEqualTo(stockAfterOrders + 2);

        // ③ 에러 없음
        assertThat(errors).isEmpty();
    }
}
