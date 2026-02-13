package com.shop.domain.wishlist.service;

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
 * 위시리스트 토글 동시성 테스트
 *
 * toggleWishlist는 exists → delete 또는 exists → insert 패턴 (TOCTOU)
 *
 * 시나리오: 같은 사용자가 같은 상품에 대해 10회 동시 토글
 * 위험: 
 *   10개 모두 "존재하지 않음" 읽기 → 10개 모두 INSERT 시도
 *   UNIQUE 제약(uk_wishlist_user_product)이 있으므로 1개만 성공하고 9개는 예외
 *   하지만 예외 처리가 없으므로 사용자에게 500 에러 노출
 *
 * 기대: 에러 없이 정상 처리 (추가 또는 삭제)
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "logging.level.org.hibernate.SQL=WARN"
})
class WishlistToggleConcurrencyTest {

    @Autowired
    private WishlistService wishlistService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testProductId;

    @BeforeEach
    void setUp() {
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true LIMIT 1",
                Long.class);

        // 초기 상태: 위시리스트에 없음
        jdbcTemplate.update(
                "DELETE FROM wishlists WHERE user_id = ? AND product_id = ?",
                testUserId, testProductId);

        System.out.println("========================================");
        System.out.println("[위시리스트 토글 테스트 준비 완료]");
        System.out.println("  사용자 ID: " + testUserId);
        System.out.println("  상품 ID: " + testProductId);
        System.out.println("========================================");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM wishlists WHERE user_id = ? AND product_id = ?",
                testUserId, testProductId);
    }

    @Test
    @DisplayName("같은 상품 10회 동시 토글 → 에러 없이 정상 처리")
    void concurrentToggle_noErrors() throws InterruptedException {
        int threadCount = 10;
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
                    wishlistService.toggleWishlist(testUserId, testProductId);
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

        // DB 상태 확인
        Integer wishlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishlists WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, testProductId);

        System.out.println("========================================");
        System.out.println("[테스트 결과]");
        System.out.println("  성공: " + successCount.get() + "회");
        System.out.println("  실패: " + failCount.get() + "회");
        System.out.println("  DB 위시리스트: " + wishlistCount + "건 (0 또는 1이어야 함)");
        if (!errors.isEmpty()) {
            System.out.println("  에러:");
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 위시리스트는 0건 또는 1건 (중복 삽입 없음)
        assertThat(wishlistCount)
                .as("위시리스트는 0건 또는 1건이어야 합니다 (현재: %d건)", wishlistCount)
                .isBetween(0, 1);

        // ② 에러 없음 (UNIQUE 위반으로 500 에러가 발생하면 안 됨)
        assertThat(failCount.get())
                .as("동시 토글 시 에러가 발생하면 안 됩니다: %s", errors)
                .isEqualTo(0);
    }
}
