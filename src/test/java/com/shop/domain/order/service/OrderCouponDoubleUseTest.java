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
 * 쿠폰 이중 사용 동시성 테스트
 *
 * 시나리오: 같은 사용자가 같은 쿠폰으로 동시에 5건 주문 시도
 * 기대 결과: 정확히 1건만 성공하고 쿠폰은 1번만 사용됨
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderCouponDoubleUseTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int CONCURRENT_ATTEMPTS = 5;

    private Long testUserId;
    private Long testProductId;
    private Long testUserCouponId;
    private Integer testCouponId;

    // 원본 상태 백업
    private int originalStock;
    private int originalSalesCount;
    private Map<String, Object> originalUserState;

    @BeforeEach
    void setUp() {
        // 1) 장바구니가 비어있는 사용자 1명 선택
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        // 사용자 원본 상태 백업
        originalUserState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserId);

        // ★ 이전 실행에서 남은 잔여 데이터 정리 (tearDown 실패 대비)
        cleanUpOrdersForUser(testUserId);

        // 2) 재고 충분한 상품 1개 선택
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        Map<String, Object> productState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);
        originalStock = ((Number) productState.get("stock_quantity")).intValue();
        originalSalesCount = ((Number) productState.get("sales_count")).intValue();

        // 3) 테스트용 쿠폰 생성
        String couponCode = "TEST_DOUBLE_" + System.currentTimeMillis();
        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, total_quantity, used_quantity, valid_from, valid_until, is_active, created_at)
                VALUES (?, '동시성테스트쿠폰', 'FIXED', 1000, 0, 100, 0, ?, ?, true, NOW())
                """,
                couponCode,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1));

        testCouponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = ?",
                Integer.class, couponCode);

        // 4) 사용자에게 쿠폰 1장 발급
        jdbcTemplate.update(
                """
                INSERT INTO user_coupons (user_id, coupon_id, is_used, issued_at, expires_at)
                VALUES (?, ?, false, NOW(), ?)
                """,
                testUserId, testCouponId,
                LocalDateTime.now().plusDays(1));

        testUserCouponId = jdbcTemplate.queryForObject(
                "SELECT user_coupon_id FROM user_coupons WHERE user_id = ? AND coupon_id = ? AND is_used = false",
                Long.class, testUserId, testCouponId);

        // 5) 장바구니에 상품 추가
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                testUserId, testProductId, now, now);

        System.out.println("========================================");
        System.out.println("[쿠폰 이중 사용 테스트 준비 완료]");
        System.out.println("  사용자 ID:     " + testUserId);
        System.out.println("  상품 ID:       " + testProductId);
        System.out.println("  쿠폰 ID:       " + testCouponId);
        System.out.println("  사용자쿠폰 ID: " + testUserCouponId);
        System.out.println("  동시 시도 수:   " + CONCURRENT_ATTEMPTS);
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown() {
        System.out.println("[정리 시작]");

        // 테스트 쿠폰 데이터 삭제
        jdbcTemplate.update("DELETE FROM user_coupons WHERE coupon_id = ?", testCouponId);
        jdbcTemplate.update("DELETE FROM coupons WHERE coupon_id = ?", testCouponId);

        // 테스트 중 생성된 주문 정리 (FK 안전)
        cleanUpOrdersForUser(testUserId);

        // 장바구니 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ? AND product_id = ?",
                testUserId, testProductId);

        // 상품 원본 상태 복원
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalStock, originalSalesCount, testProductId);

        // 사용자 원본 상태 복원
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserState.get("total_spent"),
                originalUserState.get("point_balance"),
                originalUserState.get("tier_id"),
                testUserId);

        System.out.println("[정리 완료]");
    }

    /**
     * 사용자의 최근 주문을 안전하게 삭제 (FK 참조 해제 → 주문 삭제)
     *
     * user_coupons.order_id → orders FK 참조가 있으므로,
     * 주문 삭제 전에 해당 참조를 먼저 해제해야 한다.
     */
    private void cleanUpOrdersForUser(Long userId) {
        // 1) 이 사용자의 최근 주문에 연결된 user_coupons의 FK 참조 해제
        jdbcTemplate.update(
                """
                UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL
                WHERE order_id IN (
                    SELECT order_id FROM orders
                    WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'
                )
                """,
                userId);

        // 2) 재고 이력 삭제
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE created_by = ? AND created_at > NOW() - INTERVAL '10 minutes'",
                userId);

        // 3) 주문 삭제 (order_items는 ON DELETE CASCADE)
        jdbcTemplate.update(
                "DELETE FROM orders WHERE user_id = ? AND order_date > NOW() - INTERVAL '10 minutes'",
                userId);
    }

    @Test
    @DisplayName("같은 쿠폰으로 5건 동시 주문 → 정확히 1건만 성공, 쿠폰 1회만 사용")
    void couponDoubleUse_prevention() throws InterruptedException {
        // Given
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_ATTEMPTS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger couponFailCount = new AtomicInteger(0);
        AtomicInteger cartEmptyFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 456",
                "쿠폰테스트수령인",
                "010-9876-5432",
                "CARD",
                BigDecimal.ZERO,
                testUserCouponId
        );

        // When
        for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.createOrder(testUserId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("쿠폰")) {
                        couponFailCount.incrementAndGet();
                    } else if (msg != null && msg.contains("장바구니")) {
                        cartEmptyFailCount.incrementAndGet();
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
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND order_date > NOW() - INTERVAL '5 minutes'",
                Integer.class, testUserId);

        Boolean couponIsUsed = jdbcTemplate.queryForObject(
                "SELECT is_used FROM user_coupons WHERE user_coupon_id = ?",
                Boolean.class, testUserCouponId);

        Integer couponOrderLinks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_coupon_id = ? AND order_id IS NOT NULL",
                Integer.class, testUserCouponId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  주문 성공:             " + successCount.get() + "건");
        System.out.println("  쿠폰 관련 실패:       " + couponFailCount.get() + "건");
        System.out.println("  장바구니 비어있음:     " + cartEmptyFailCount.get() + "건");
        System.out.println("  기타 실패:             " + otherFailCount.get() + "건");
        System.out.println("  ─────────────────────────────");
        System.out.println("  DB 주문 수:            " + orderCount);
        System.out.println("  쿠폰 사용 상태:        " + couponIsUsed);
        System.out.println("  쿠폰-주문 연결:        " + couponOrderLinks);
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 주문은 최대 1건만 성공
        assertThat(successCount.get())
                .as("같은 사용자의 동시 주문은 최대 1건만 성공해야 합니다")
                .isEqualTo(1);

        // ② DB 주문 레코드 == 성공 횟수
        assertThat(orderCount)
                .as("DB 주문 수와 성공 횟수가 일치해야 합니다")
                .isEqualTo(successCount.get());

        // ③ 쿠폰은 사용됨 상태
        assertThat(couponIsUsed)
                .as("쿠폰은 사용됨 상태여야 합니다")
                .isTrue();

        // ④ 쿠폰이 연결된 주문은 정확히 1건
        assertThat(couponOrderLinks)
                .as("쿠폰-주문 연결은 정확히 1건이어야 합니다")
                .isEqualTo(1);

        // ⑤ 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외가 발생하면 안 됩니다: %s", errors)
                .isEqualTo(0);
    }
}
