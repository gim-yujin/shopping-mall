package com.shop.domain.coupon.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 발급 동시성 테스트
 *
 * 시나리오 1 — 초과 발급 (Over-Issuance)
 *   총 수량 5장인 쿠폰에 20명이 동시 발급 요청
 *   위험: Coupon에 락 없이 usedQuantity를 읽으므로 20명 모두 "수량 남음"으로 판단
 *   기대: 정확히 5명만 성공
 *
 * 시나리오 2 — 중복 발급 (Duplicate Issuance)
 *   같은 사용자가 같은 쿠폰을 5회 동시 요청
 *   위험: (user_id, coupon_id) UNIQUE 제약이 없으므로 여러 장 발급 가능
 *   기대: 정확히 1장만 발급
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class CouponIssuanceConcurrencyTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 시나리오 1: 초과 발급
    // =========================================================================

    private Integer overIssueCouponId;
    private String overIssueCouponCode;
    private List<Long> testUserIds;

    // =========================================================================
    // 시나리오 2: 중복 발급
    // =========================================================================

    private Integer dupCouponId;
    private String dupCouponCode;
    private Long dupTestUserId;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String testName = testInfo.getDisplayName();

        if (testName.contains("초과 발급")) {
            setUpOverIssuanceTest();
        } else if (testName.contains("중복 발급")) {
            setUpDuplicateIssuanceTest();
        }
    }

    private void setUpOverIssuanceTest() {
        // 총 수량 5장 쿠폰 생성
        overIssueCouponCode = "TEST_OVER_" + System.currentTimeMillis();
        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, total_quantity, used_quantity, valid_from, valid_until, is_active, created_at)
                VALUES (?, '초과발급테스트', 'FIXED', 1000, 0, 5, 0, ?, ?, true, NOW())
                """,
                overIssueCouponCode,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1));

        overIssueCouponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = ?",
                Integer.class, overIssueCouponCode);

        // 20명의 사용자 선택
        testUserIds = jdbcTemplate.queryForList(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                ORDER BY u.user_id LIMIT 20
                """,
                Long.class);

        System.out.println("========================================");
        System.out.println("[초과 발급 테스트 준비 완료]");
        System.out.println("  쿠폰 ID: " + overIssueCouponId);
        System.out.println("  총 수량: 5장");
        System.out.println("  동시 요청: " + testUserIds.size() + "명");
        System.out.println("========================================");
    }

    private void setUpDuplicateIssuanceTest() {
        // 수량 충분한 쿠폰 생성 (수량 초과가 아닌 중복만 테스트)
        dupCouponCode = "TEST_DUP_" + System.currentTimeMillis();
        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, total_quantity, used_quantity, valid_from, valid_until, is_active, created_at)
                VALUES (?, '중복발급테스트', 'FIXED', 500, 0, 100, 0, ?, ?, true, NOW())
                """,
                dupCouponCode,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1));

        dupCouponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = ?",
                Integer.class, dupCouponCode);

        // 사용자 1명 선택
        dupTestUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        System.out.println("========================================");
        System.out.println("[중복 발급 테스트 준비 완료]");
        System.out.println("  쿠폰 ID: " + dupCouponId);
        System.out.println("  사용자 ID: " + dupTestUserId);
        System.out.println("  동시 요청: 5회");
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        System.out.println("[정리 시작]");
        String testName = testInfo.getDisplayName();

        if (testName.contains("초과 발급") && overIssueCouponId != null) {
            jdbcTemplate.update("DELETE FROM user_coupons WHERE coupon_id = ?", overIssueCouponId);
            jdbcTemplate.update("DELETE FROM coupons WHERE coupon_id = ?", overIssueCouponId);
        } else if (testName.contains("중복 발급") && dupCouponId != null) {
            jdbcTemplate.update("DELETE FROM user_coupons WHERE coupon_id = ?", dupCouponId);
            jdbcTemplate.update("DELETE FROM coupons WHERE coupon_id = ?", dupCouponId);
        }

        System.out.println("[정리 완료]");
    }

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 총 5장 쿠폰에 20명 동시 발급 → 초과 발급 방지")
    void overIssuance_prevention() throws InterruptedException {
        int threadCount = testUserIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger invalidFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // When: 20명이 동시에 같은 쿠폰 발급 요청
        for (int i = 0; i < threadCount; i++) {
            final Long userId = testUserIds.get(i);
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponService.issueCoupon(userId, overIssueCouponCode);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("유효하지 않은") || msg.contains("수량"))) {
                        invalidFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("User#" + userId + "(시도#" + attempt + "): "
                                + e.getClass().getSimpleName() + " - " + msg);
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

        // Then
        Integer issuedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE coupon_id = ?",
                Integer.class, overIssueCouponId);

        Integer usedQuantity = jdbcTemplate.queryForObject(
                "SELECT used_quantity FROM coupons WHERE coupon_id = ?",
                Integer.class, overIssueCouponId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  발급 성공:          " + successCount.get() + "명");
        System.out.println("  수량 부족 실패:     " + invalidFailCount.get() + "명");
        System.out.println("  기타 실패:          " + otherFailCount.get() + "명");
        System.out.println("  ─────────────────────────────");
        System.out.println("  DB 발급 수:         " + issuedCount + "장 (기대: 5장)");
        System.out.println("  used_quantity:      " + usedQuantity + " (기대: 5)");
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 실제 발급 수 == 총 수량 (5장)
        assertThat(issuedCount)
                .as("총 5장 쿠폰이므로 실제 발급은 5장이어야 합니다 (현재: %d장 초과 발급)", issuedCount)
                .isEqualTo(5);

        // ② used_quantity == 총 수량
        assertThat(usedQuantity)
                .as("used_quantity는 총 수량(5)과 같아야 합니다")
                .isEqualTo(5);

        // ③ 성공 수 == 발급 수
        assertThat(successCount.get())
                .as("성공 횟수와 DB 발급 수가 일치해야 합니다")
                .isEqualTo(issuedCount);

        // ④ 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 같은 사용자가 같은 쿠폰 5회 동시 발급 → 중복 발급 방지")
    void duplicateIssuance_prevention() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyIssuedCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // When: 같은 사용자가 같은 쿠폰을 5회 동시 요청
        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    couponService.issueCoupon(dupTestUserId, dupCouponCode);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("이미 발급")) {
                        alreadyIssuedCount.incrementAndGet();
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

        // Then
        Integer issuedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE coupon_id = ? AND user_id = ?",
                Integer.class, dupCouponId, dupTestUserId);

        Integer usedQuantity = jdbcTemplate.queryForObject(
                "SELECT used_quantity FROM coupons WHERE coupon_id = ?",
                Integer.class, dupCouponId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  발급 성공:          " + successCount.get() + "회");
        System.out.println("  이미 발급됨:        " + alreadyIssuedCount.get() + "회");
        System.out.println("  기타 실패:          " + otherFailCount.get() + "회");
        System.out.println("  ─────────────────────────────");
        System.out.println("  DB 발급 수:         " + issuedCount + "장 (기대: 1장)");
        System.out.println("  used_quantity:      " + usedQuantity + " (기대: 1)");
        if (!errors.isEmpty()) {
            System.out.println("  기타 에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 실제 발급 수 == 1장
        assertThat(issuedCount)
                .as("같은 사용자에게 같은 쿠폰은 1장만 발급되어야 합니다 (현재: %d장)", issuedCount)
                .isEqualTo(1);

        // ② used_quantity == 1
        assertThat(usedQuantity)
                .as("used_quantity는 1이어야 합니다")
                .isEqualTo(1);

        // ③ 기타 예외 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }
}
