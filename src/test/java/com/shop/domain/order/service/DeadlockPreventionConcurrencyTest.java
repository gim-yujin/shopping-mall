package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
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
 * [Phase 9] 교차 상품 주문 데드락 방지 스트레스 테스트.
 *
 * <h3>테스트 대상 동시성 메커니즘</h3>
 * <ul>
 *   <li>[Phase 8] findAllByIdInWithLock()의 product_id 정렬 기반 잠금 순서 보장</li>
 *   <li>cartItems.sort(Comparator.comparing(productId))에 의한 자원 획득 순서 통일</li>
 *   <li>PostgreSQL PESSIMISTIC_WRITE 행 잠금의 직렬화 동작</li>
 * </ul>
 *
 * <h3>데드락 시나리오 (잠금 순서가 없을 때)</h3>
 * <pre>
 *   User A 장바구니: [상품1, 상품2]  →  LOCK 상품1 → 대기(상품2)
 *   User B 장바구니: [상품2, 상품1]  →  LOCK 상품2 → 대기(상품1)
 *   → 순환 대기(Circular Wait) → 데드락 발생!
 * </pre>
 *
 * <h3>데드락 방지 메커니즘</h3>
 * <p>OrderCreationService.resolveCartItems()에서 cartItems를 productId 오름차순으로 정렬한다.
 * 이후 deductStockAndBuildOrderLines()에서 findAllByIdInWithLock(sorted productIds)를 호출하면
 * 모든 트랜잭션이 동일한 순서(productId 오름차순)로 잠금을 획득한다.
 * 이 순서 보장 덕분에 순환 대기가 원천적으로 불가능하다.</p>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>데드락 없이 타임아웃(10초) 내에 모든 작업 완료</li>
 *   <li>모든 사용자의 주문이 성공 (재고 충분 조건)</li>
 *   <li>각 상품의 최종 재고 == 초기 재고 - 해당 상품 총 주문 수량</li>
 *   <li>예상치 못한 예외 없음</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class DeadlockPreventionConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;

    /** 테스트 사용자 4명 (교차 상품 주문) */
    private List<Long> testUserIds;

    /** 테스트 상품 3개 (교차 조합용) */
    private List<Long> testProductIds;

    /** 상품별 초기 재고 — 모든 주문이 성공할 수 있도록 넉넉히 설정 */
    private static final int STOCK_PER_PRODUCT = 100;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();

        // [Phase 9] 4명의 사용자와 3개의 상품을 생성한다.
        // 각 사용자가 서로 다른 상품 조합을 장바구니에 담아,
        // 잠금 순서가 없으면 데드락이 발생할 수 있는 상황을 만든다.
        testUserIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            testUserIds.add(fixture.createActiveUser());
        }

        testProductIds = fixture.createActiveProducts(3, STOCK_PER_PRODUCT);

        // 교차 상품 조합: 각 사용자가 서로 다른 2개 상품을 장바구니에 담는다.
        //
        // [Phase 9] 교차 잠금 시나리오 설계:
        //   User 0: [상품0, 상품1] — 상품0 → 상품1 순서로 잠금
        //   User 1: [상품1, 상품2] — 상품1 → 상품2 순서로 잠금
        //   User 2: [상품0, 상품2] — 상품0 → 상품2 순서로 잠금
        //   User 3: [상품0, 상품1, 상품2] — 상품0 → 상품1 → 상품2 순서로 잠금
        //
        // 잠금 순서가 보장되지 않으면:
        //   User 0이 상품1을 먼저 잠그고, User 1이 상품1을 기다리면서 상품2를 잠그고,
        //   User 2가 상품2를 기다리면서 상품0을 잠그고, User 0이 상품0을 기다리면
        //   → 순환 대기 → 데드락
        //
        // productId 오름차순 정렬이 보장되면:
        //   모든 사용자가 낮은 productId → 높은 productId 순으로 잠금을 획득하므로
        //   순환 대기가 구조적으로 불가능하다.
        int[][] productCombinations = {
                {0, 1},       // User 0: 상품0, 상품1
                {1, 2},       // User 1: 상품1, 상품2
                {0, 2},       // User 2: 상품0, 상품2
                {0, 1, 2}     // User 3: 상품0, 상품1, 상품2
        };

        String now = LocalDateTime.now().toString();
        for (int userIdx = 0; userIdx < testUserIds.size(); userIdx++) {
            Long userId = testUserIds.get(userIdx);
            for (int productIdx : productCombinations[userIdx]) {
                Long productId = testProductIds.get(productIdx);
                jdbcTemplate.update(
                        "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                        userId, productId, now, now);
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (Long userId : testUserIds) {
            jdbcTemplate.update("DELETE FROM point_history WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM product_inventory_history WHERE created_by = ?", userId);
            jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", userId);
        }
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 교차 상품 동시 주문 — 데드락 없이 모두 성공
    // =========================================================================

    /**
     * [Phase 9] 4명의 사용자가 교차하는 상품 조합으로 동시에 주문 → 데드락 없이 모두 성공.
     *
     * <p><b>핵심 검증:</b> 이 테스트가 타임아웃 없이 완료된다는 것 자체가
     * 데드락 방지 메커니즘이 동작한다는 증거이다.
     * PostgreSQL의 기본 deadlock_timeout은 1초이므로, 데드락이 발생하면
     * 최소 1개 트랜잭션이 "deadlock detected" 에러로 실패한다.</p>
     *
     * <p><b>시간 제한:</b> 모든 작업이 10초 내에 완료되어야 한다.
     * 데드락이 감지되면 PostgreSQL이 1개 트랜잭션을 강제 취소하고,
     * 재시도 없이 에러가 전파되어 테스트가 실패한다.</p>
     */
    @Test
    @DisplayName("교차 상품 4명 동시 주문 → 데드락 없이 10초 내 모두 성공")
    void overlappingProductOrders_noDeadlock_allSucceed() throws InterruptedException {
        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);

        AtomicInteger successCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 4; i++) {
            final Long userId = testUserIds.get(i);
            final int userNum = i + 1;

            executor.submit(() -> {
                ready.countDown();
                try {
                    gate.await();

                    OrderCreateRequest request = new OrderCreateRequest(
                            "데드락테스트 주소",
                            "테스터" + userNum,
                            "010-0000-" + String.format("%04d", userNum),
                            "CARD",
                            BigDecimal.ZERO,
                            null, 0, null
                    );
                    orderService.createOrder(userId, request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add("User#" + userNum + ": "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            gate.countDown();

            // [Phase 9] 10초 타임아웃: 데드락이 발생하면 PostgreSQL의 deadlock_timeout(1초) 후
            // 1개 트랜잭션이 취소되더라도, 나머지 트랜잭션이 직렬 실행되어 완료되기까지
            // 최대 수 초가 소요될 수 있다. 10초는 충분한 여유를 둔 타임아웃이다.
            assertThat(done.await(10, TimeUnit.SECONDS))
                    .as("데드락 없이 10초 내에 모든 주문이 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // ── 불변식 검증 ──

        // ① 예상치 못한 에러 없음 (데드락 에러 포함)
        assertThat(errors)
                .as("데드락이나 예상치 못한 예외가 없어야 합니다: %s", errors)
                .isEmpty();

        // ② 4명 모두 주문 성공
        assertThat(successCount.get())
                .as("모든 사용자(4명)의 주문이 성공해야 합니다")
                .isEqualTo(4);

        // ③ 각 상품의 재고가 정확히 차감되었는지 검증
        //    상품0: User0(1개) + User2(1개) + User3(1개) = 3개 차감
        //    상품1: User0(1개) + User1(1개) + User3(1개) = 3개 차감
        //    상품2: User1(1개) + User2(1개) + User3(1개) = 3개 차감
        int[] expectedDeductions = {3, 3, 3};
        for (int i = 0; i < testProductIds.size(); i++) {
            Integer finalStock = jdbcTemplate.queryForObject(
                    "SELECT stock_quantity FROM products WHERE product_id = ?",
                    Integer.class, testProductIds.get(i));

            assertThat(finalStock)
                    .as("상품%d의 최종 재고 = %d - %d = %d이어야 합니다",
                            i, STOCK_PER_PRODUCT, expectedDeductions[i],
                            STOCK_PER_PRODUCT - expectedDeductions[i])
                    .isEqualTo(STOCK_PER_PRODUCT - expectedDeductions[i]);
        }

        // ④ 합리적인 시간 내에 완료 (데드락 발생 시 최소 1초+ 지연)
        assertThat(elapsed)
                .as("전체 작업이 10초 내에 완료되어야 합니다 (elapsed: %dms)", elapsed)
                .isLessThan(10_000L);
    }
}
