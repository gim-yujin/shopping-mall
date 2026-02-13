package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.global.exception.InsufficientStockException;
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
 * 주문 생성 동시성 테스트 — Overselling 방지 검증
 *
 * 시나리오: 재고 5개인 상품에 10명이 동시 주문
 * 기대 결과: 정확히 5명만 성공, 재고 = 0, 마이너스 재고 없음
 *
 * createOrder()는 단일 @Transactional 안에서 7개 테이블을 수정:
 * products, orders, order_items, carts, product_inventory_history, users, user_coupons
 *
 * PESSIMISTIC_WRITE 락으로 재고 보호 → 이 테스트가 이를 검증
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderOversellingTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 설정
    private static final int STOCK = 5;         // 초기 재고
    private static final int USERS = 10;        // 동시 주문 사용자 수

    // 테스트 대상
    private Long testProductId;
    private List<Long> testUserIds;

    // 원본 상태 백업 (복원용)
    private int originalStock;
    private int originalSalesCount;
    private Map<Long, Map<String, Object>> originalUserStates = new HashMap<>();

    @BeforeEach
    void setUp() {
        // 1) 활성 상품 1개 선택 (재고 충분한 것)
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= ? LIMIT 1",
                Long.class, STOCK);

        // 원본 상태 백업
        Map<String, Object> productState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);
        originalStock = ((Number) productState.get("stock_quantity")).intValue();
        originalSalesCount = ((Number) productState.get("sales_count")).intValue();

        // 재고를 정확히 STOCK으로 설정
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = 0 WHERE product_id = ?",
                STOCK, testProductId);

        // 2) 테스트용 사용자 10명 선택 (기존 장바구니가 없는 사용자)
        testUserIds = jdbcTemplate.queryForList(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true
                  AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id
                LIMIT ?
                """,
                Long.class, USERS);

        if (testUserIds.size() < USERS) {
            throw new RuntimeException(
                    "테스트 가능한 사용자가 부족합니다. (필요: " + USERS + ", 확보: " + testUserIds.size() + ")");
        }

        // 사용자 원본 상태 백업
        for (Long userId : testUserIds) {
            Map<String, Object> userState = jdbcTemplate.queryForMap(
                    "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                    userId);
            originalUserStates.put(userId, userState);
        }

        // 3) 각 사용자의 장바구니에 테스트 상품 1개씩 추가
        String now = LocalDateTime.now().toString();
        for (Long userId : testUserIds) {
            jdbcTemplate.update(
                    "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    userId, testProductId, now, now);
        }

        System.out.println("========================================");
        System.out.println("[Overselling 테스트 준비 완료]");
        System.out.println("  상품 ID: " + testProductId);
        System.out.println("  초기 재고: " + STOCK);
        System.out.println("  동시 주문자 수: " + USERS);
        System.out.println("  사용자 IDs: " + testUserIds);
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown() {
        System.out.println("[정리 시작]");

        // 1) 테스트 중 생성된 주문 관련 데이터 삭제
        //    order_items는 ON DELETE CASCADE이므로 orders만 삭제하면 됨
        for (Long userId : testUserIds) {
            // 재고 이력 삭제
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE product_id = ? AND created_by = ?",
                    testProductId, userId);

            // 주문 삭제 (order_items는 CASCADE)
            jdbcTemplate.update(
                    "DELETE FROM orders WHERE user_id = ? AND order_date > NOW() - INTERVAL '5 minutes'",
                    userId);
        }

        // 2) 남은 장바구니 정리
        for (Long userId : testUserIds) {
            jdbcTemplate.update(
                    "DELETE FROM carts WHERE user_id = ? AND product_id = ?",
                    userId, testProductId);
        }

        // 3) 상품 원본 상태 복원
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalStock, originalSalesCount, testProductId);

        // 4) 사용자 원본 상태 복원
        for (Map.Entry<Long, Map<String, Object>> entry : originalUserStates.entrySet()) {
            Long userId = entry.getKey();
            Map<String, Object> state = entry.getValue();
            jdbcTemplate.update(
                    "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                    state.get("total_spent"), state.get("point_balance"), state.get("tier_id"), userId);
        }

        System.out.println("[정리 완료] 모든 데이터가 원본 상태로 복원되었습니다.");
    }

    /**
     * 핵심 테스트: 재고 5개, 10명 동시 주문 → 정확히 5명만 성공
     *
     * PESSIMISTIC_WRITE 락이 올바르게 동작하면:
     * - 각 트랜잭션이 순차적으로 재고를 차감
     * - 재고 소진 후 나머지는 InsufficientStockException
     * - 최종 재고 = 0 (마이너스 불가)
     */
    @Test
    @DisplayName("재고 5개 상품에 10명 동시 주문 → 정확히 5명만 성공, 재고 = 0")
    void overselling_prevention() throws InterruptedException {
        // Given
        int poolSize = USERS;
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch ready = new CountDownLatch(USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(USERS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger stockFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123",
                "테스트수령인",
                "010-1234-5678",
                "CARD",
                BigDecimal.ZERO,
                null  // 쿠폰 미사용
        );

        // When: 10명 동시 주문
        for (int i = 0; i < USERS; i++) {
            final Long userId = testUserIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.createOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    stockFailCount.incrementAndGet();
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                    errors.add("userId=" + userId + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();                      // 동시 출발!
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: DB에서 직접 검증
        Integer finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        Integer finalSalesCount = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        Integer createdOrderCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM orders
                WHERE user_id IN (%s) AND order_date > NOW() - INTERVAL '5 minutes'
                """.formatted(testUserIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")),
                Integer.class);

        // 결과 출력
        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  주문 성공:            " + successCount.get() + "명");
        System.out.println("  재고 부족 실패:       " + stockFailCount.get() + "명");
        System.out.println("  기타 실패:            " + otherFailCount.get() + "명");
        System.out.println("  ─────────────────────────────");
        System.out.println("  최종 재고:            " + finalStock);
        System.out.println("  판매 수량:            " + finalSalesCount);
        System.out.println("  생성된 주문 수 (DB):   " + createdOrderCount);
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러 목록:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 재고 마이너스 방지 (가장 치명적인 검증)
        assertThat(finalStock)
                .as("재고는 절대 마이너스가 되면 안 됩니다")
                .isGreaterThanOrEqualTo(0);

        // ② 정확히 STOCK명만 성공
        assertThat(successCount.get())
                .as("재고 %d개이므로 정확히 %d명만 주문 성공해야 합니다", STOCK, STOCK)
                .isEqualTo(STOCK);

        // ③ 나머지는 재고 부족으로 실패
        assertThat(stockFailCount.get())
                .as("나머지 %d명은 재고 부족으로 실패해야 합니다", USERS - STOCK)
                .isEqualTo(USERS - STOCK);

        // ④ 재고 = 0 (전부 소진)
        assertThat(finalStock)
                .as("재고가 모두 소진되어 0이어야 합니다")
                .isEqualTo(0);

        // ⑤ 판매 수량 == 초기 재고
        assertThat(finalSalesCount)
                .as("판매 수량이 초기 재고와 일치해야 합니다")
                .isEqualTo(STOCK);

        // ⑥ DB 주문 레코드 수 == 성공 횟수
        assertThat(createdOrderCount)
                .as("DB의 주문 수가 성공 횟수와 일치해야 합니다")
                .isEqualTo(successCount.get());

        // ⑦ 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("재고 부족 외의 예외가 발생하면 안 됩니다: %s", errors)
                .isEqualTo(0);
    }
}
