package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.global.exception.InsufficientStockException;
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
 * [Phase 9] 다중 사용자 재고 경합 스트레스 테스트.
 *
 * <h3>테스트 대상 동시성 메커니즘</h3>
 * <ul>
 *   <li>Product 엔티티의 PESSIMISTIC_WRITE 잠금 (findAllByIdInWithLock)</li>
 *   <li>재고 차감의 원자성 (decreaseStock → stockQuantity CHECK 제약)</li>
 *   <li>판매량(salesCount) 증가의 정확성</li>
 * </ul>
 *
 * <h3>시나리오: 한정 수량 상품에 대한 다중 사용자 동시 주문</h3>
 * <p>재고 3개인 상품에 10명의 사용자가 동시에 1개씩 주문한다.
 * 비관적 잠금이 없으면 10명 모두 재고 3을 읽고 통과하여 과매도(overselling)가 발생한다.
 * 비관적 잠금이 올바르게 동작하면 정확히 3명만 성공하고 7명은 재고 부족으로 실패한다.</p>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>성공 주문 수 == 초기 재고 (3건)</li>
 *   <li>최종 재고 == 0 (과매도 없음, 재고 누수 없음)</li>
 *   <li>최종 판매량 == 초기 판매량 + 성공 주문 수</li>
 *   <li>실패 주문은 모두 InsufficientStockException</li>
 *   <li>예상치 못한 예외 없음</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 동시 10스레드가 각각 커넥션을 잡으므로 풀 크기를 넉넉히 설정
        "spring.datasource.hikari.maximum-pool-size=30",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class StockRaceConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    /** 테스트용 격리된 데이터를 관리하는 컨텍스트 */
    private TestDataFactory.FixtureContext fixture;

    /** 테스트용으로 생성한 사용자 ID 목록 (10명) */
    private List<Long> testUserIds;

    /** 테스트용으로 생성한 한정 수량 상품 ID */
    private Long testProductId;

    /** 한정 수량 — 10명 중 이 수만큼만 주문 성공 가능 */
    private static final int LIMITED_STOCK = 3;

    /** 동시 주문 시도 사용자 수 */
    private static final int CONCURRENT_USERS = 10;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();

        // [Phase 9] 테스트 격리를 위해 전용 사용자 10명과 한정 수량 상품 1개를 생성한다.
        // 기존 시드 데이터에 의존하지 않으므로 테스트 간 간섭이 없다.
        testUserIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            testUserIds.add(fixture.createActiveUser());
        }

        testProductId = fixture.createActiveProduct(LIMITED_STOCK);

        // 각 사용자의 장바구니에 해당 상품 1개씩 추가
        String now = LocalDateTime.now().toString();
        for (Long userId : testUserIds) {
            jdbcTemplate.update(
                    "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    userId, testProductId, now, now);
        }
    }

    @AfterEach
    void tearDown() {
        // 테스트에서 생성된 주문 및 관련 데이터 정리
        for (Long userId : testUserIds) {
            jdbcTemplate.update(
                    "DELETE FROM point_history WHERE user_id = ?", userId);
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE created_by = ?", userId);
            jdbcTemplate.update(
                    "DELETE FROM orders WHERE user_id = ?", userId);
            jdbcTemplate.update(
                    "DELETE FROM carts WHERE user_id = ?", userId);
        }
        // TestDataFactory가 생성한 상품, 사용자, 카테고리 정리
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 한정 수량 상품에 대한 다중 사용자 동시 주문
    // =========================================================================

    /**
     * [Phase 9] 재고 3개 상품에 10명이 동시 주문 → 정확히 3명만 성공, 과매도(overselling) 방지.
     *
     * <p><b>동시성 위험:</b> 비관적 잠금이 없으면 10개 스레드가 모두 stock_quantity=3을 읽고
     * "재고 충분" 판정을 내린 후 decreaseStock()을 실행한다.
     * stock_quantity는 3 → 2 → 1 → 0 → -1 → ... → -7까지 감소하여 과매도가 발생한다.
     * (CHECK 제약 chk_stock이 물리적 방어선이지만, 비즈니스 레벨에서 먼저 차단해야 한다)</p>
     *
     * <p><b>비관적 잠금 동작:</b> findAllByIdInWithLock()이 SELECT ... FOR UPDATE를 실행하면,
     * 첫 번째 스레드가 Product 행을 잠그고 재고를 차감한다. 두 번째 스레드는 잠금 해제를 기다린 후
     * 갱신된 재고를 읽는다. 3번째까지는 성공하고, 4번째부터는 stock_quantity=0을 읽어
     * InsufficientStockException이 발생한다.</p>
     */
    @Test
    @DisplayName("재고 3개 상품에 10명 동시 주문 → 정확히 3명만 성공, 과매도 방지")
    void multiUserStockRace_exactlyLimitedStockSucceeds() throws InterruptedException {
        // 주문 생성 전 상품 상태 기록
        int initialSalesCount = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger stockFailCount = new AtomicInteger(0);
        List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        // [Phase 9] 10명의 사용자가 동시에 주문을 시도한다.
        // CountDownLatch(start)로 모든 스레드가 준비된 후 동시에 출발하도록 동기화한다.
        // 이렇게 해야 실제 경합 상황을 재현할 수 있다.
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final Long userId = testUserIds.get(i);
            final int attempt = i + 1;

            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();

                    OrderCreateRequest request = new OrderCreateRequest(
                            "스트레스테스트 주소",
                            "테스터" + attempt,
                            "010-0000-" + String.format("%04d", attempt),
                            "CARD",
                            BigDecimal.ZERO,
                            null, 0, null
                    );
                    orderService.createOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    // 기대되는 실패: 재고 부족
                    stockFailCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (unexpectedErrors) {
                        unexpectedErrors.add("사용자#" + attempt + ": "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
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
            // 모든 스레드가 준비된 후 동시에 출발
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("지정 시간 내 모든 작업이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        // ── DB에서 최종 상태 직접 검증 ──────────────────────────

        Integer finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        Integer finalSalesCount = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        Integer createdOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id IN ("
                        + "SELECT DISTINCT reference_id FROM product_inventory_history "
                        + "WHERE product_id = ? AND change_type = 'OUT' AND reason = 'ORDER'"
                        + ")",
                Integer.class, testProductId);

        // ① 예상치 못한 에러가 없어야 한다
        assertThat(unexpectedErrors)
                .as("예상치 못한 예외가 발생하면 안 됩니다")
                .isEmpty();

        // ② 성공 주문 수 == 한정 수량 (정확히 3건)
        assertThat(successCount.get())
                .as("한정 재고(%d)만큼만 주문이 성공해야 합니다 (과매도 방지)", LIMITED_STOCK)
                .isEqualTo(LIMITED_STOCK);

        // ③ 재고 부족 실패 수 == 전체 시도 - 성공 수
        assertThat(stockFailCount.get())
                .as("나머지 %d명은 재고 부족으로 실패해야 합니다", CONCURRENT_USERS - LIMITED_STOCK)
                .isEqualTo(CONCURRENT_USERS - LIMITED_STOCK);

        // ④ 최종 재고 == 0 (과매도 없음, 재고 누수 없음)
        assertThat(finalStock)
                .as("최종 재고는 0이어야 합니다 (과매도: 음수, 누수: 양수)")
                .isEqualTo(0);

        // ⑤ 판매량 == 초기 판매량 + 성공 주문 수
        assertThat(finalSalesCount)
                .as("판매량은 정확히 %d만큼 증가해야 합니다", LIMITED_STOCK)
                .isEqualTo(initialSalesCount + LIMITED_STOCK);

        // ⑥ DB에 실제로 생성된 주문 수 == 성공 주문 수
        assertThat(createdOrderCount)
                .as("DB의 실제 주문 수가 성공 카운트와 일치해야 합니다")
                .isEqualTo(LIMITED_STOCK);
    }
}
