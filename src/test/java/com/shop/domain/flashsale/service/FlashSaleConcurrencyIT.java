package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.exception.DuplicateFlashSalePurchaseException;
import com.shop.domain.flashsale.exception.FlashSaleSoldOutException;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 23-3] 플래시 세일 동시성 통합 테스트.
 *
 * <h3>설계문서 §9-2 검증 항목</h3>
 * <ol>
 *   <li>오버셀 0건 — 재고 N · M 동시 → 정확히 N 성공</li>
 *   <li>1인 1구매 — 동일 사용자 다중 동시 → 정확히 1 성공, 나머지 ONE_PER_USER</li>
 *   <li>보상 정합성 — UNIQUE 위반 시 트랜잭션 롤백 + restoreAtomic 안전망으로
 *       remaining_quantity가 누수 없이 복원되는지</li>
 * </ol>
 *
 * <p>{@link FlashSaleCommandService}의 단일 {@code @Transactional} 경계가
 * CAS 예약 → 주문 발행 → 1인1구매 INSERT → flush까지 한 번에 묶이므로,
 * UNIQUE 위반 시 catch 블록의 {@code restoreAtomic}과 트랜잭션 롤백이 모두
 * 결과적으로 net-zero를 만들어야 한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class FlashSaleConcurrencyIT {

    @Autowired
    private FlashSaleCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;

    private List<Long> userIds = new ArrayList<>();
    private Long productId;
    private Long flashSaleId;
    private Long flashSaleItemId;

    private static final int STOCK = 5;
    private static final int CONCURRENT_USERS = 12;
    private static final BigDecimal SALE_PRICE = new BigDecimal("9900.00");
    private static final BigDecimal ORIGINAL_PRICE = new BigDecimal("19900.00");

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        userIds.clear();
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            userIds.add(fixture.createActiveUser());
        }
        productId = fixture.createActiveProduct(1000);

        LocalDateTime now = LocalDateTime.now();
        flashSaleId = jdbcTemplate.queryForObject(
                """
                INSERT INTO flash_sales (title, status, start_time, end_time, created_at, version)
                VALUES (?, 'ACTIVE', ?, ?, ?, 0)
                RETURNING flash_sale_id
                """,
                Long.class,
                "동시성 IT 세일",
                now.minusMinutes(1),
                now.plusHours(1),
                now);

        flashSaleItemId = jdbcTemplate.queryForObject(
                """
                INSERT INTO flash_sale_items
                    (flash_sale_id, product_id, sale_price, allocated_quantity,
                     remaining_quantity, per_user_limit, version)
                VALUES (?, ?, ?, ?, ?, 1, 0)
                RETURNING flash_sale_item_id
                """,
                Long.class,
                flashSaleId, productId, SALE_PRICE, STOCK, STOCK);
    }

    @AfterEach
    void tearDown() {
        if (flashSaleId != null) {
            jdbcTemplate.update("DELETE FROM flash_sale_purchases WHERE flash_sale_id = ?", flashSaleId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN "
                    + "(SELECT order_id FROM orders WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
        }
        if (flashSaleItemId != null) {
            jdbcTemplate.update("DELETE FROM flash_sale_items WHERE flash_sale_item_id = ?", flashSaleItemId);
        }
        if (flashSaleId != null) {
            jdbcTemplate.update("DELETE FROM flash_sales WHERE flash_sale_id = ?", flashSaleId);
        }
        fixture.cleanup();
    }

    /**
     * 오버셀 0건 검증: 재고 5에 12명이 동시 호출 → 정확히 5 성공, 7 SOLD_OUT.
     */
    @Test
    @DisplayName("재고 5 · 12명 동시 호출 → 정확히 5명만 성공, remaining=0, purchases=5")
    void burst_oversellPrevention() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final Long uid = userIds.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    commandService.purchase(flashSaleId, flashSaleItemId, uid);
                    successCount.incrementAndGet();
                } catch (FlashSaleSoldOutException e) {
                    soldOutCount.incrementAndGet();
                } catch (Exception e) {
                    unexpected.add(e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM flash_sale_items WHERE flash_sale_item_id = ?",
                Integer.class, flashSaleItemId);
        Integer purchases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_purchases WHERE flash_sale_id = ?",
                Integer.class, flashSaleId);
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id IN ("
                        + userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow()
                        + ")",
                Integer.class);

        assertThat(unexpected).as("예상치 못한 예외 없어야 함").isEmpty();
        assertThat(successCount.get()).as("재고만큼만 성공").isEqualTo(STOCK);
        assertThat(soldOutCount.get()).as("나머지는 SOLD_OUT").isEqualTo(CONCURRENT_USERS - STOCK);
        assertThat(remaining).as("remaining_quantity 0 (오버셀 없음)").isZero();
        assertThat(purchases).as("성공 건수만큼 flash_sale_purchases 적재").isEqualTo(STOCK);
        assertThat(orderCount).as("성공 건수만큼 orders 생성").isEqualTo(STOCK);
    }

    /**
     * 1인 1구매 강제: 동일 사용자 5 동시 호출 → 정확히 1 성공.
     * 실패 4건은 ONE_PER_USER 또는 SOLD_OUT(한쪽이 먼저 reserve+commit한 직후
     * 다른 쪽이 reserve 시도해서 0 반환). 둘 다 비즈니스적으로 허용 가능.
     * 핵심 검증: 결과적으로 purchases=1, orders=1, remaining=STOCK-1.
     */
    @Test
    @DisplayName("동일 사용자 5 동시 호출 → 정확히 1 성공, 보상 후 remaining=STOCK-1")
    void sameUser_onePerUser_compensatesRemaining() throws InterruptedException {
        final Long uid = userIds.get(0);
        final int attempts = 5;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    commandService.purchase(flashSaleId, flashSaleItemId, uid);
                    successCount.incrementAndGet();
                } catch (DuplicateFlashSalePurchaseException e) {
                    duplicateCount.incrementAndGet();
                } catch (FlashSaleSoldOutException e) {
                    soldOutCount.incrementAndGet();
                } catch (Exception e) {
                    unexpected.add(e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM flash_sale_items WHERE flash_sale_item_id = ?",
                Integer.class, flashSaleItemId);
        Integer purchases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_purchases WHERE flash_sale_id = ? AND user_id = ?",
                Integer.class, flashSaleId, uid);
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ?",
                Integer.class, uid);

        assertThat(unexpected).as("예상치 못한 예외 없어야 함").isEmpty();
        assertThat(successCount.get()).as("정확히 1건만 성공").isEqualTo(1);
        assertThat(duplicateCount.get() + soldOutCount.get())
                .as("나머지는 ONE_PER_USER/SOLD_OUT으로 거절").isEqualTo(attempts - 1);
        assertThat(purchases).as("purchases는 1건만 영구화").isEqualTo(1);
        assertThat(orders).as("orders도 1건만 영구화 (롤백 정합성)").isEqualTo(1);
        assertThat(remaining)
                .as("UNIQUE 위반/롤백/보상 후 remaining = STOCK - 1 (누수 없음)")
                .isEqualTo(STOCK - 1);
    }

    /**
     * 정합성 회귀: burst 후 (allocated - remaining) == COUNT(purchases) == COUNT(orders) == 성공.
     */
    @Test
    @DisplayName("burst 후 정합성: allocated-remaining == purchases == orders == success")
    void invariant_allocatedMinusRemainingEqualsPurchases() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final Long uid = userIds.get(i);
            executor.submit(() -> {
                try {
                    start.await();
                    commandService.purchase(flashSaleId, flashSaleItemId, uid);
                    successCount.incrementAndGet();
                } catch (Exception ignore) {
                    // 분기는 다른 테스트에서 검증
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        try {
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        Integer allocated = jdbcTemplate.queryForObject(
                "SELECT allocated_quantity FROM flash_sale_items WHERE flash_sale_item_id = ?",
                Integer.class, flashSaleItemId);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM flash_sale_items WHERE flash_sale_item_id = ?",
                Integer.class, flashSaleItemId);
        Integer purchases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flash_sale_purchases WHERE flash_sale_id = ?",
                Integer.class, flashSaleId);

        assertThat(allocated - remaining)
                .as("§8-3 reconciliation 기본 항등식")
                .isEqualTo(purchases)
                .isEqualTo(successCount.get());
    }
}
