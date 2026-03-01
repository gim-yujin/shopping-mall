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
 * [Step 6] 반품 워크플로우 동시성 테스트 + 회귀 검증.
 *
 * <h3>목적</h3>
 *
 * <p>Step 1~5에서 구현한 반품 승인 워크플로우(사용자 반품 신청 → 관리자 승인/거절)에
 * 동시성 문제가 없는지 검증한다. 기존 부분 취소 동시성 테스트
 * ({@link PartialCancellationConcurrencyTest})와 동일한 인프라 패턴을 사용하되,
 * 반품 고유의 상태 전이 경합 시나리오를 추가한다.</p>
 *
 * <h3>핵심 동시성 위험 분석</h3>
 *
 * <p>반품 워크플로우에서 발생할 수 있는 동시성 위험은 세 가지 유형으로 분류된다.</p>
 *
 * <p><b>(1) 상태 전이 경합 — 같은 아이템에 대한 중복 반품 신청:</b><br>
 * 두 요청이 동시에 NORMAL → RETURN_REQUESTED 전이를 시도할 때, Order 비관적 잠금이
 * 없으면 둘 다 status=NORMAL을 읽고 전이에 성공하여 pendingReturnQuantity가 이중
 * 기록된다. Order PESSIMISTIC_WRITE 잠금으로 직렬화하여 하나만 성공해야 한다.</p>
 *
 * <p><b>(2) 교차 작업 경합 — 반품 신청과 부분 취소의 동시 실행:</b><br>
 * 같은 주문의 같은 아이템에 대해 반품 신청(DELIVERED 상태)과 부분 취소(PENDING/PAID 상태)가
 * 동시에 실행될 때, Order 잠금으로 직렬화되어 먼저 진입한 쪽만 성공해야 한다.
 * 단, 반품은 DELIVERED 상태에서만, 부분 취소는 PENDING/PAID 상태에서만 가능하므로
 * 정상적인 플로우에서는 둘 다 실행될 수 없다. 이 테스트는 같은 DELIVERED 주문에서
 * 반품 신청과 부분 취소가 동시에 시도되는 비정상 경합을 검증한다.</p>
 *
 * <p><b>(3) 관리자 처리 경합 — 동시 승인/거절:</b><br>
 * 두 관리자(또는 같은 관리자의 이중 클릭)가 동시에 승인과 거절을 시도할 때,
 * Order 잠금으로 직렬화되어 하나만 성공하고 나머지는 상태 불일치로 실패해야 한다.
 * 재고 복구와 환불이 정확히 한 번만 수행되는지 검증한다.</p>
 *
 * <h3>검증 시나리오</h3>
 *
 * <table>
 *   <tr><th>시나리오</th><th>검증 내용</th></tr>
 *   <tr><td>1. 같은 아이템 동시 반품 신청</td><td>하나만 성공, 나머지 BusinessException</td></tr>
 *   <tr><td>2. 반품 신청 + 부분 취소 동시</td><td>하나만 성공 (Order 락 직렬화)</td></tr>
 *   <tr><td>3. 동시 승인/거절</td><td>하나만 성공, 재고/환불 정확</td></tr>
 *   <tr><td>4. 다중 사용자 동시 반품 신청</td><td>각각 독립 성공, 재고 미변경</td></tr>
 *   <tr><td>5. 동시 승인 이중 클릭 방지</td><td>한 번만 환불, 재고 정확</td></tr>
 * </table>
 *
 * <h3>회귀 검증</h3>
 * <p>기존 부분 취소/전체 취소 테스트({@link PartialCancellationConcurrencyTest},
 * {@link CancelOrderConcurrencyTest})가 변경 없이 통과하는지 전체 스위트에서 확인한다.
 * 이 클래스 자체의 tearDown에서 테스트 데이터를 완전히 정리하여 다른 테스트에 영향을 주지 않는다.</p>
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
class ReturnConcurrencyTest {

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
        ensurePointHistoryReferenceTypeConstraint();

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

        // 장바구니 비어있는 사용자 2명 선택
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

    /**
     * point_history 테이블의 reference_type CHECK 제약에 'RETURN' 타입이 포함되도록 보장.
     *
     * <p>[Step 6 회귀 방어] 반품 승인 시 PointHistory에 reference_type='RETURN'이
     * 기록되는데, 기존 CHECK 제약에 RETURN이 없으면 INSERT가 실패하여 전체 트랜잭션이
     * 롤백된다. 이를 테스트 레벨에서 사전 보장한다.</p>
     */
    private void ensurePointHistoryReferenceTypeConstraint() {
        jdbcTemplate.execute("ALTER TABLE point_history DROP CONSTRAINT IF EXISTS chk_point_reference_type");
        jdbcTemplate.execute("""
                ALTER TABLE point_history
                ADD CONSTRAINT chk_point_reference_type CHECK (
                    reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN', 'ADMIN', 'SYSTEM')
                )
                """);
    }

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

    /**
     * 주문 생성 헬퍼.
     *
     * <p>상품A와 상품B(선택)를 장바구니에 담고 주문을 생성한다.
     * 쿠폰/포인트를 사용하지 않는 단순 카드 결제 주문이다.</p>
     */
    private Order createTestOrder(Long userId, Long productIdA, int qtyA,
                                  Long productIdB, int qtyB) {
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

    /**
     * DELIVERED 상태의 주문을 생성한다.
     *
     * <p>반품은 DELIVERED 상태에서만 가능하므로, 주문 생성 후
     * PENDING → (PAID →) SHIPPED → DELIVERED 상태 전이를 수행한다.
     * OrderService.updateOrderStatus를 사용하여 실제 서비스 로직
     * (포인트 정산, 배송일시 기록 등)이 모두 실행되도록 한다.</p>
     */
    private Order createDeliveredOrder(Long userId, Long productIdA, int qtyA,
                                       Long productIdB, int qtyB) {
        Order order = createTestOrder(userId, productIdA, qtyA, productIdB, qtyB);
        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");
        return order;
    }

    private Long findOrderItemId(Long orderId, Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_item_id FROM order_items WHERE order_id = ? AND product_id = ?",
                Long.class, orderId, productId);
    }

    // =========================================================================
    // 시나리오 1: 같은 아이템 동시 반품 신청 — 하나만 RETURN_REQUESTED 성공
    // =========================================================================

    /**
     * 같은 주문의 같은 아이템에 대해 5개 스레드가 동시에 반품을 신청한다.
     *
     * <h3>동시성 위험</h3>
     * <p>[위험] Order에 비관적 잠금이 없었을 때:
     * 5개 스레드가 동시에 status=NORMAL을 읽고 RETURN_REQUESTED로 전이를 시도한다.
     * 모두 유효하다고 판단하여 5건 모두 성공하고, pendingReturnQuantity가
     * 5로 기록되어 실제 잔여 수량을 초과하는 반품이 등록된다.</p>
     *
     * <h3>기대 동작</h3>
     * <p>Order PESSIMISTIC_WRITE 잠금으로 동일 주문에 대한 요청이 직렬화된다.
     * 첫 번째 스레드가 RETURN_REQUESTED로 전이에 성공하면, 이후 스레드는
     * status가 더 이상 NORMAL이 아니므로 상태 전이 실패(BusinessException)로
     * 거부된다. 정확히 1건만 성공해야 한다.</p>
     *
     * <h3>재고 불변 조건</h3>
     * <p>반품 신청은 상태 전이만 수행하고 재고를 변경하지 않으므로,
     * 성공/실패와 무관하게 재고가 변하지 않아야 한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("시나리오 1: 같은 아이템 5회 동시 반품 신청 → 1회만 성공, 재고 불변")
    void sameItem_concurrentReturnRequest_onlyOneSucceeds() throws InterruptedException {
        // Given: 상품A 3개 DELIVERED 주문
        Order order = createDeliveredOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemId = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        int stockAfterOrder = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[시나리오 1: 같은 아이템 동시 반품 신청]");
        System.out.println("  주문 ID: " + orderId + ", 상품A 3개 (DELIVERED)");
        System.out.println("  5개 스레드가 동시에 1개씩 반품 신청 → 1건만 성공 기대");
        System.out.println("========================================");

        // When: 5개 스레드 동시 실행
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger statusFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.requestReturn(orderId, testUserIdA, orderItemId, 1, "DEFECT");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    // 상태 전이 실패 또는 수량 부족
                    if (msg != null && (msg.contains("상태를") || msg.contains("수량"))) {
                        statusFailCount.incrementAndGet();
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
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemId);
        Integer pendingQty = jdbcTemplate.queryForObject(
                "SELECT pending_return_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemId);
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  성공: " + successCount.get() + "건, 상태 전이 실패: "
                + statusFailCount.get() + "건, 기타: " + otherFailCount.get() + "건");
        System.out.println("  최종 상태: " + finalStatus + ", pendingReturnQuantity: " + pendingQty);
        System.out.println("  재고: " + stockAfterOrder + " → " + finalStock + " (불변 기대)");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 정확히 1건만 성공
        //    Order 잠금으로 직렬화 → 첫 번째만 NORMAL→RETURN_REQUESTED 성공
        //    이후 스레드는 status=RETURN_REQUESTED → 전이 불가 (RETURN_REQUESTED→RETURN_REQUESTED 미허용)
        assertThat(successCount.get())
                .as("Order 잠금으로 직렬화되어 정확히 1건만 반품 신청에 성공해야 한다")
                .isEqualTo(1);

        // ② 상태는 RETURN_REQUESTED
        assertThat(finalStatus)
                .as("성공한 1건의 결과로 상태가 RETURN_REQUESTED여야 한다")
                .isEqualTo("RETURN_REQUESTED");

        // ③ pendingReturnQuantity = 1 (이중 기록 없음)
        assertThat(pendingQty)
                .as("pendingReturnQuantity는 정확히 1이어야 한다 (lost update 없음)")
                .isEqualTo(1);

        // ④ 재고 불변 (반품 신청은 재고를 변경하지 않음)
        assertThat(finalStock)
                .as("반품 신청 단계에서는 재고가 변하지 않아야 한다")
                .isEqualTo(stockAfterOrder);

        // ⑤ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 에러가 없어야 한다: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 2: 반품 신청 + 부분 취소 동시 — Order 잠금 직렬화
    // =========================================================================

    /**
     * DELIVERED 상태의 같은 주문에서 한 스레드는 반품 신청, 다른 스레드는 부분 취소를 동시에 실행한다.
     *
     * <h3>동시성 위험</h3>
     * <p>[위험] 두 작업이 독립적으로 진입하면, 반품 신청은 pendingReturnQuantity를 증가시키고
     * 부분 취소는 cancelledQuantity를 증가시켜 getRemainingQuantity가 음수가 될 수 있다.
     * 또한 DELIVERED 상태에서는 부분 취소가 허용되지 않으므로(PENDING/PAID에서만 허용),
     * 부분 취소 시도는 상태 검증에서 실패해야 한다.</p>
     *
     * <h3>기대 동작</h3>
     * <p>Order 잠금으로 직렬화된다. DELIVERED 상태이므로:
     * - 반품 신청은 성공한다 (DELIVERED에서 허용)
     * - 부분 취소는 "부분 취소 가능한 주문 상태가 아닙니다" 에러로 실패한다
     * 두 작업이 데드락 없이 빠르게 완료되어야 한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("시나리오 2: 반품 신청 + 부분 취소 동시 → 반품만 성공, 데드락 없음")
    void returnRequestVsPartialCancel_noDeadlock() throws InterruptedException {
        // Given: 상품A 3개 DELIVERED 주문
        Order order = createDeliveredOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemId = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        System.out.println("========================================");
        System.out.println("[시나리오 2: 반품 신청 + 부분 취소 동시]");
        System.out.println("  주문 ID: " + orderId + " (DELIVERED 상태)");
        System.out.println("  스레드A: 반품 1개 신청 / 스레드B: 부분 취소 1개");
        System.out.println("========================================");

        // When
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger returnSuccess = new AtomicInteger(0);
        AtomicInteger cancelSuccess = new AtomicInteger(0);
        List<String> returnErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> cancelErrors = Collections.synchronizedList(new ArrayList<>());

        // Thread A: 반품 신청
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.requestReturn(orderId, testUserIdA, orderItemId, 1, "DEFECT");
                returnSuccess.incrementAndGet();
            } catch (Exception e) {
                returnErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread B: 부분 취소 (DELIVERED 상태이므로 실패 기대)
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.partialCancel(orderId, testUserIdA, orderItemId, 1);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                cancelErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
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
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemId);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  반품 신청: 성공=" + returnSuccess.get() + ", 에러=" + returnErrors);
        System.out.println("  부분 취소: 성공=" + cancelSuccess.get() + ", 에러=" + cancelErrors);
        System.out.println("  최종 아이템 상태: " + finalStatus);
        System.out.println("  소요 시간: " + elapsed + "ms");
        System.out.println("========================================");

        // ① 데드락 없이 시간 내 완료
        assertThat(completedInTime)
                .as("30초 내에 완료되어야 한다 (데드락 없음)")
                .isTrue();

        // ② 반품 신청은 성공
        assertThat(returnSuccess.get())
                .as("DELIVERED 상태에서 반품 신청은 성공해야 한다")
                .isEqualTo(1);

        // ③ 부분 취소는 실패 (DELIVERED 상태에서 부분 취소 불가)
        assertThat(cancelSuccess.get())
                .as("DELIVERED 상태에서 부분 취소는 실패해야 한다")
                .isEqualTo(0);

        // ④ 최종 상태는 RETURN_REQUESTED
        assertThat(finalStatus).isEqualTo("RETURN_REQUESTED");

        // ⑤ 빠르게 완료 (데드락 시 lock_timeout까지 대기)
        assertThat(elapsed)
                .as("데드락이 없다면 빠르게 완료되어야 한다 (5초 이내)")
                .isLessThan(5000);
    }

    // =========================================================================
    // 시나리오 3: 동시 승인/거절 — 하나만 성공, 재고/환불 정확
    // =========================================================================

    /**
     * RETURN_REQUESTED 상태의 같은 아이템에 대해 한 스레드는 승인, 다른 스레드는 거절을 동시에 실행한다.
     *
     * <h3>동시성 위험</h3>
     * <p>[위험] 관리자 두 명이 동시에 같은 반품 건을 처리할 때:
     * - 승인이 먼저 실행되면: 재고 복구 + 환불 수행 → 거절 시도 시 상태 불일치
     * - 거절이 먼저 실행되면: 상태만 변경 → 승인 시도 시 상태 불일치
     * Order 잠금 없이 둘 다 RETURN_REQUESTED를 읽으면 양쪽 모두 실행되어
     * 재고 이중 복구 또는 상태 불일치가 발생한다.</p>
     *
     * <h3>기대 동작</h3>
     * <p>Order 잠금으로 직렬화 → 먼저 진입한 쪽만 성공.
     * - 승인이 성공하면: 재고 +1 복구, 환불 기록, 상태 RETURNED
     * - 거절이 성공하면: 재고 불변, 환불 없음, 상태 RETURN_REJECTED
     * 어느 쪽이든 정확히 하나만 성공해야 한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("시나리오 3: 동시 승인/거절 → 하나만 성공, 재고/환불 정확")
    void concurrentApproveAndReject_onlyOneSucceeds() throws InterruptedException {
        // Given: DELIVERED 주문 → 반품 1개 신청
        Order order = createDeliveredOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemId = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        orderService.requestReturn(orderId, testUserIdA, orderItemId, 1, "WRONG_ITEM");

        int stockBeforeApproval = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[시나리오 3: 동시 승인/거절]");
        System.out.println("  주문 ID: " + orderId + ", 아이템 상태: RETURN_REQUESTED");
        System.out.println("  스레드A: 승인 / 스레드B: 거절 (동시 실행)");
        System.out.println("========================================");

        // When
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger approveSuccess = new AtomicInteger(0);
        AtomicInteger rejectSuccess = new AtomicInteger(0);
        List<String> approveErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> rejectErrors = Collections.synchronizedList(new ArrayList<>());

        // Thread A: 승인
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.approveReturn(orderId, orderItemId);
                approveSuccess.incrementAndGet();
            } catch (Exception e) {
                approveErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        // Thread B: 거절
        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.rejectReturn(orderId, orderItemId, "관리자 거절 테스트");
                rejectSuccess.incrementAndGet();
            } catch (Exception e) {
                rejectErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemId);
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, orderId);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  승인: 성공=" + approveSuccess.get() + ", 에러=" + approveErrors);
        System.out.println("  거절: 성공=" + rejectSuccess.get() + ", 에러=" + rejectErrors);
        System.out.println("  최종 상태: " + finalStatus);
        System.out.println("  재고 변화: " + stockBeforeApproval + " → " + finalStock);
        System.out.println("  환불 금액: " + refundedAmount);
        System.out.println("========================================");

        // ① 정확히 하나만 성공
        int totalSuccess = approveSuccess.get() + rejectSuccess.get();
        assertThat(totalSuccess)
                .as("승인/거절 중 정확히 하나만 성공해야 한다")
                .isEqualTo(1);

        // ② 승인이 성공한 경우: 재고 복구 + 환불 기록 + 상태 RETURNED
        if (approveSuccess.get() == 1) {
            assertThat(finalStatus).isEqualTo("RETURNED");
            assertThat(finalStock)
                    .as("승인 성공 시 재고 1개 복구")
                    .isEqualTo(stockBeforeApproval + 1);
            assertThat(refundedAmount)
                    .as("승인 성공 시 환불 금액이 기록되어야 한다")
                    .isGreaterThan(BigDecimal.ZERO);
        }

        // ③ 거절이 성공한 경우: 재고 불변 + 환불 없음 + 상태 RETURN_REJECTED
        if (rejectSuccess.get() == 1) {
            assertThat(finalStatus).isEqualTo("RETURN_REJECTED");
            assertThat(finalStock)
                    .as("거절 성공 시 재고 변화 없음")
                    .isEqualTo(stockBeforeApproval);
            assertThat(refundedAmount)
                    .as("거절 성공 시 환불 금액이 없어야 한다")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // 시나리오 4: 다중 사용자 동시 반품 신청 — 사용자 간 간섭 없음
    // =========================================================================

    /**
     * 서로 다른 사용자의 서로 다른 주문에 대해 동시 반품 신청을 요청한다.
     *
     * <h3>핵심 검증</h3>
     * <p>서로 다른 주문이므로 Order 잠금이 서로 간섭하지 않아 양쪽 모두 성공해야 한다.
     * 반품 신청은 재고를 변경하지 않으므로, 같은 상품이더라도 재고 경합이 발생하지 않는다.
     * (재고 경합은 관리자 승인 시점에 발생하며, 이는 기존 Product 잠금으로 처리된다.)</p>
     *
     * <p>이 시나리오는 반품 워크플로우의 설계 결정 — "반품 신청 시 재고를 변경하지 않고
     * 승인 시에만 변경한다" — 이 올바르게 동작하는지 확인한다. 만약 반품 신청 시에도
     * 재고를 조정했다면, 여기서 Product 잠금 경합이 불필요하게 발생했을 것이다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("시나리오 4: 다중 사용자 동시 반품 신청 → 각각 독립 성공, 재고 불변")
    void multiUser_concurrentReturnRequest_independentSuccess() throws InterruptedException {
        // Given: 사용자A와 사용자B가 각각 같은 상품A를 3개씩 DELIVERED 주문
        Order orderA = createDeliveredOrder(testUserIdA, testProductIdA, 3, null, 0);
        Order orderB = createDeliveredOrder(testUserIdB, testProductIdA, 3, null, 0);

        Long orderItemIdA = findOrderItemId(orderA.getOrderId(), testProductIdA);
        Long orderItemIdB = findOrderItemId(orderB.getOrderId(), testProductIdA);

        int stockAfterOrders = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        // 주문 2건 생성 후 재고: 원본 - 6
        assertThat(stockAfterOrders).isEqualTo(originalStockA - 6);

        System.out.println("========================================");
        System.out.println("[시나리오 4: 다중 사용자 동시 반품 신청]");
        System.out.println("  사용자A 주문: " + orderA.getOrderId()
                + ", 사용자B 주문: " + orderB.getOrderId());
        System.out.println("  각각 상품A 1개씩 동시 반품 신청 → 양쪽 성공, 재고 불변 기대");
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
                orderService.requestReturn(orderA.getOrderId(), testUserIdA, orderItemIdA, 1, "DEFECT");
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
                orderService.requestReturn(orderB.getOrderId(), testUserIdB, orderItemIdB, 1, "WRONG_ITEM");
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
        String statusA = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdA);
        String statusB = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdB);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  A 성공: " + successA.get() + ", B 성공: " + successB.get());
        System.out.println("  상태A: " + statusA + ", 상태B: " + statusB);
        System.out.println("  재고: " + stockAfterOrders + " → " + finalStock + " (불변 기대)");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 양쪽 모두 성공
        assertThat(successA.get()).isEqualTo(1);
        assertThat(successB.get()).isEqualTo(1);

        // ② 양쪽 모두 RETURN_REQUESTED 상태
        assertThat(statusA).isEqualTo("RETURN_REQUESTED");
        assertThat(statusB).isEqualTo("RETURN_REQUESTED");

        // ③ 재고 불변 (반품 신청은 재고를 변경하지 않음)
        assertThat(finalStock)
                .as("반품 신청 단계에서 재고가 변하지 않아야 한다")
                .isEqualTo(stockAfterOrders);

        // ④ 에러 없음
        assertThat(errors).isEmpty();
    }

    // =========================================================================
    // 시나리오 5: 동시 승인 이중 클릭 방지 — 환불/재고 복구 정확히 1회
    // =========================================================================

    /**
     * RETURN_REQUESTED 상태의 같은 아이템에 대해 3개 스레드가 동시에 승인을 시도한다.
     *
     * <h3>동시성 위험</h3>
     * <p>[위험] 관리자가 승인 버튼을 빠르게 여러 번 클릭하거나, 네트워크 지연으로
     * 같은 요청이 중복 전송될 때:
     * - 3개 스레드가 동시에 status=RETURN_REQUESTED를 읽고 승인을 시도
     * - 재고 복구가 3번 실행되어 재고가 3배로 복구
     * - 환불 금액이 3배로 기록</p>
     *
     * <h3>기대 동작</h3>
     * <p>Order 잠금으로 직렬화 → 첫 번째만 성공.
     * 이후 스레드는 status가 RETURNED(종결)이므로 상태 검증에서 실패.
     * 재고는 정확히 1개만 복구되고, 환불 금액도 한 번만 기록된다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("시나리오 5: 승인 이중 클릭 → 1회만 환불, 재고 정확히 1개 복구")
    void doubleClickApprove_onlyOneSucceeds() throws InterruptedException {
        // Given: DELIVERED 주문 → 반품 2개 신청
        Order order = createDeliveredOrder(testUserIdA, testProductIdA, 5, null, 0);
        Long orderItemId = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        orderService.requestReturn(orderId, testUserIdA, orderItemId, 2, "DEFECT");

        int stockBeforeApproval = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        System.out.println("========================================");
        System.out.println("[시나리오 5: 승인 이중 클릭]");
        System.out.println("  주문 ID: " + orderId + ", 반품 신청 수량: 2개");
        System.out.println("  3개 스레드가 동시에 승인 → 1건만 성공, 재고 +2 기대");
        System.out.println("========================================");

        // When: 3개 스레드 동시 승인
        int threadCount = 3;
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
                    orderService.approveReturn(orderId, orderItemId);
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
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemId);
        int finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, orderId);
        Integer returnedQty = jdbcTemplate.queryForObject(
                "SELECT returned_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemId);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  성공: " + successCount.get() + "건, 실패: " + failCount.get() + "건");
        System.out.println("  최종 상태: " + finalStatus);
        System.out.println("  재고: " + stockBeforeApproval + " → " + finalStock
                + " (기대: " + (stockBeforeApproval + 2) + ")");
        System.out.println("  returnedQuantity: " + returnedQty + " (기대: 2)");
        System.out.println("  환불 금액: " + refundedAmount);
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("  에러: " + e));
        }
        System.out.println("========================================");

        // ① 정확히 1건만 성공
        assertThat(successCount.get())
                .as("이중 클릭 방지: 정확히 1건만 승인에 성공해야 한다")
                .isEqualTo(1);

        // ② 상태는 RETURNED
        assertThat(finalStatus).isEqualTo("RETURNED");

        // ③ 재고 정확히 2개 복구 (pendingReturnQuantity=2 승인)
        assertThat(finalStock)
                .as("재고는 정확히 반품 수량(2개)만큼만 복구되어야 한다")
                .isEqualTo(stockBeforeApproval + 2);

        // ④ returnedQuantity = 2
        assertThat(returnedQty)
                .as("returnedQuantity는 2여야 한다 (이중 기록 없음)")
                .isEqualTo(2);

        // ⑤ 환불 금액 > 0 (정확히 한 번만 기록)
        assertThat(refundedAmount).isGreaterThan(BigDecimal.ZERO);

        // ⑥ 실결제금액 초과 환불 없음
        BigDecimal effectivePaid = order.getFinalAmount().subtract(order.getShippingFee());
        assertThat(refundedAmount)
                .as("환불 누계가 실결제금액(배송비 제외)을 초과하면 안 된다")
                .isLessThanOrEqualTo(effectivePaid);
    }

    // =========================================================================
    // 시나리오 6 (회귀): 반품 신청 후 전체 취소 경합 — 데드락 없이 하나만 성공
    // =========================================================================

    /**
     * DELIVERED 주문에서 반품 신청과 전체 취소가 동시에 시도되는 경합을 검증한다.
     *
     * <h3>배경</h3>
     * <p>DELIVERED 상태에서는 전체 취소(cancelOrder)가 허용되지 않는다
     * (isCancellable()이 false). 따라서 전체 취소는 실패하고 반품 신청만 성공해야 한다.
     * 이 테스트는 두 작업이 Order 잠금에서 경합할 때 데드락 없이 완료되는지와,
     * 기존 전체 취소 로직이 반품 워크플로우 도입 후에도 정상 동작하는지 회귀 검증한다.</p>
     */
    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("시나리오 6 (회귀): DELIVERED 주문에서 반품 신청 vs 전체 취소 → 데드락 없이 반품만 성공")
    void returnRequestVsFullCancel_onDelivered_noDeadlock() throws InterruptedException {
        // Given: DELIVERED 주문
        Order order = createDeliveredOrder(testUserIdA, testProductIdA, 3, null, 0);
        Long orderItemId = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderId = order.getOrderId();

        System.out.println("========================================");
        System.out.println("[시나리오 6: 반품 신청 vs 전체 취소 (DELIVERED)]");
        System.out.println("  주문 ID: " + orderId + " (DELIVERED)");
        System.out.println("========================================");

        // When
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger returnSuccess = new AtomicInteger(0);
        AtomicInteger cancelSuccess = new AtomicInteger(0);
        List<String> returnErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> cancelErrors = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.requestReturn(orderId, testUserIdA, orderItemId, 1, "DEFECT");
                returnSuccess.incrementAndGet();
            } catch (Exception e) {
                returnErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            ready.countDown();
            try {
                start.await();
                orderService.cancelOrder(orderId, testUserIdA);
                cancelSuccess.incrementAndGet();
            } catch (Exception e) {
                cancelErrors.add(e.getClass().getSimpleName() + " - " + e.getMessage());
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

        String finalOrderStatus = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, orderId);

        System.out.println("========================================");
        System.out.println("[결과]");
        System.out.println("  반품 신청: 성공=" + returnSuccess.get() + ", 에러=" + returnErrors);
        System.out.println("  전체 취소: 성공=" + cancelSuccess.get() + ", 에러=" + cancelErrors);
        System.out.println("  주문 상태: " + finalOrderStatus);
        System.out.println("  소요 시간: " + elapsed + "ms");
        System.out.println("========================================");

        // ① 데드락 없이 빠르게 완료
        assertThat(completedInTime).isTrue();
        assertThat(elapsed).isLessThan(5000);

        // ② 반품 신청 성공
        assertThat(returnSuccess.get()).isEqualTo(1);

        // ③ 전체 취소 실패 (DELIVERED 상태에서 취소 불가)
        assertThat(cancelSuccess.get()).isEqualTo(0);

        // ④ 주문 상태는 DELIVERED 유지 (취소되지 않음)
        assertThat(finalOrderStatus).isEqualTo("DELIVERED");
    }
}
