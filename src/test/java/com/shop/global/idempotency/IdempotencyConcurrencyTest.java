package com.shop.global.idempotency;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.service.OrderService;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Phase 9] 멱등성 키 동시성 스트레스 테스트.
 *
 * <h3>테스트 대상 동시성 메커니즘</h3>
 * <ul>
 *   <li>idempotency_records 테이블의 UNIQUE(user_id, idempotency_key) 제약</li>
 *   <li>IdempotencyService.initRecord()의 REQUIRES_NEW 트랜잭션 격리</li>
 *   <li>DataIntegrityViolationException 기반 동시 중복 감지</li>
 * </ul>
 *
 * <h3>시나리오 1: 동일 멱등성 키로 10회 동시 initRecord 호출</h3>
 * <p>네트워크 지연이나 로드 밸런서에 의해 같은 요청이 여러 서버 인스턴스에
 * 동시에 도착하는 상황을 시뮬레이션한다. UNIQUE 제약이 물리적으로
 * 중복 레코드 생성을 차단하므로, 정확히 1건만 PROCESSING 상태로 생성되고
 * 나머지는 DataIntegrityViolationException으로 거부된다.</p>
 *
 * <h3>시나리오 2: 멱등성 키 + 주문 생성 E2E 동시 호출</h3>
 * <p>OrderController의 멱등성 흐름을 서비스 레벨에서 재현한다.
 * 같은 사용자가 같은 멱등성 키로 동시에 주문을 시도하면,
 * 1건만 PROCESSING → 주문 생성 → COMPLETED로 전이되고,
 * 나머지는 PROCESSING 또는 UNIQUE 위반으로 차단되어 중복 주문이 방지된다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class IdempotencyConcurrencyTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private Long testUserId;
    private Long testProductId;

    /** 동시 요청 수 */
    private static final int CONCURRENT_REQUESTS = 10;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        testUserId = fixture.createActiveUser();
        testProductId = fixture.createActiveProduct(50);
    }

    @AfterEach
    void tearDown() {
        // 멱등성 레코드 정리
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE user_id = ?", testUserId);
        // 주문 관련 데이터 정리
        jdbcTemplate.update("DELETE FROM point_history WHERE user_id = ?", testUserId);
        jdbcTemplate.update("DELETE FROM product_inventory_history WHERE created_by = ?", testUserId);
        jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", testUserId);
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 동일 멱등성 키로 동시 initRecord — 1건만 성공
    // =========================================================================

    /**
     * [Phase 9] 같은 (userId, idempotencyKey)로 10회 동시 initRecord → 1건만 PROCESSING 생성.
     *
     * <p><b>동시성 위험:</b> UNIQUE 제약 없이 SELECT + INSERT 패턴을 사용하면,
     * 10개 스레드가 모두 "기존 레코드 없음"을 읽은 후 INSERT를 시도하여
     * 10개의 PROCESSING 레코드가 생성될 수 있다.
     * 이 경우 같은 주문이 10번 실행되어 10건의 중복 주문이 발생한다.</p>
     *
     * <p><b>UNIQUE 제약 동작:</b> PostgreSQL의 uk_idempotency_user_key 제약이
     * 첫 번째 INSERT만 허용하고, 나머지 9건은 DataIntegrityViolationException으로 거부한다.
     * initRecord()는 REQUIRES_NEW 전파로 독립 트랜잭션에서 실행되므로,
     * INSERT 성공이 즉시 커밋되어 후속 요청이 PROCESSING 상태를 조회할 수 있다.</p>
     */
    @Test
    @DisplayName("같은 멱등성 키로 10회 동시 initRecord → 정확히 1건만 PROCESSING 생성")
    void concurrentInitRecord_onlyOneSucceeds() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger uniqueViolationCount = new AtomicInteger(0);
        List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    idempotencyService.initRecord(testUserId, idempotencyKey, "ORDER");
                    successCount.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    // 기대되는 실패: UNIQUE(user_id, idempotency_key) 위반
                    uniqueViolationCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (unexpectedErrors) {
                        unexpectedErrors.add("시도#" + attempt + ": "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        // DB에서 실제 레코드 수 검증
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, testUserId, idempotencyKey);

        // ① 예상치 못한 에러 없음
        assertThat(unexpectedErrors)
                .as("예상치 못한 예외가 없어야 합니다")
                .isEmpty();

        // ② 정확히 1건만 성공
        assertThat(successCount.get())
                .as("UNIQUE 제약에 의해 정확히 1건만 initRecord가 성공해야 합니다")
                .isEqualTo(1);

        // ③ 나머지는 UNIQUE 위반으로 실패
        assertThat(uniqueViolationCount.get())
                .as("나머지 %d건은 UNIQUE 위반으로 실패해야 합니다", CONCURRENT_REQUESTS - 1)
                .isEqualTo(CONCURRENT_REQUESTS - 1);

        // ④ DB에 실제로 1건만 존재
        assertThat(recordCount)
                .as("DB에 멱등성 레코드가 정확히 1건만 존재해야 합니다")
                .isEqualTo(1);
    }

    // =========================================================================
    // 시나리오 2: 멱등성 키 + 주문 생성 E2E 동시 호출
    // =========================================================================

    /**
     * [Phase 9] 같은 멱등성 키로 5회 동시 주문 요청 → 정확히 1건만 주문 생성.
     *
     * <p><b>E2E 흐름 시뮬레이션:</b> OrderController의 멱등성 패턴을 서비스 레벨에서 재현한다.
     * 각 스레드가 다음 순서로 실행한다:</p>
     * <ol>
     *   <li>findExisting() — 기존 레코드 확인</li>
     *   <li>initRecord() — PROCESSING 레코드 생성 (UNIQUE 위반 시 중단)</li>
     *   <li>orderService.createOrder() — 실제 주문 생성</li>
     *   <li>markCompletedForSsr() — COMPLETED 전환</li>
     * </ol>
     *
     * <p><b>동시성 보장:</b> initRecord()의 UNIQUE 제약이 1단계 방어선,
     * advisory lock(acquireUserCartLock)이 2단계 방어선 역할을 한다.
     * 두 메커니즘이 독립적으로 동작하여 이중 보호를 제공한다.</p>
     */
    @Test
    @DisplayName("같은 멱등성 키로 5회 동시 주문 요청 → 주문은 정확히 1건만 생성")
    void concurrentOrderWithSameIdempotencyKey_onlyOneOrderCreated() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();

        // 장바구니에 상품 추가
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                testUserId, testProductId, now, now);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger orderCreatedCount = new AtomicInteger(0);
        AtomicInteger idempotencyBlockedCount = new AtomicInteger(0);
        AtomicInteger emptyCartCount = new AtomicInteger(0);
        List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    executeIdempotentOrder(testUserId, idempotencyKey, attempt);
                    orderCreatedCount.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    // 멱등성 키 UNIQUE 위반 — 기대되는 실패
                    idempotencyBlockedCount.incrementAndGet();
                } catch (com.shop.global.exception.BusinessException e) {
                    String code = e.getCode();
                    if ("EMPTY_CART".equals(code) || "IDEMPOTENCY_CONFLICT".equals(code)) {
                        // 장바구니가 이미 소비됨 또는 멱등성 충돌 — 기대되는 실패
                        emptyCartCount.incrementAndGet();
                    } else {
                        synchronized (unexpectedErrors) {
                            unexpectedErrors.add("시도#" + attempt + ": ["
                                    + code + "] " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    synchronized (unexpectedErrors) {
                        unexpectedErrors.add("시도#" + attempt + ": "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        // DB에서 실제 주문 수 검증
        Integer actualOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ?",
                Integer.class, testUserId);

        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, testUserId, idempotencyKey);

        // ① 예상치 못한 에러 없음
        assertThat(unexpectedErrors)
                .as("예상치 못한 예외가 없어야 합니다: %s", unexpectedErrors)
                .isEmpty();

        // ② DB에 주문이 정확히 1건만 존재 (중복 주문 없음)
        assertThat(actualOrderCount)
                .as("멱등성 키에 의해 주문은 정확히 1건만 생성되어야 합니다")
                .isEqualTo(1);

        // ③ 주문 성공은 1건
        assertThat(orderCreatedCount.get())
                .as("주문 생성 성공은 1건이어야 합니다")
                .isEqualTo(1);

        // ④ 나머지는 멱등성 차단 또는 장바구니 소진으로 실패
        int totalBlocked = idempotencyBlockedCount.get() + emptyCartCount.get();
        assertThat(totalBlocked)
                .as("나머지 %d건은 멱등성 차단 또는 장바구니 소진으로 실패해야 합니다",
                        threadCount - 1)
                .isEqualTo(threadCount - 1);

        // ⑤ 멱등성 레코드는 1건만 존재
        assertThat(recordCount)
                .as("멱등성 레코드는 1건만 존재해야 합니다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("executeWithCompletion은 COMPLETED 전환 실패 시 action에서 저장한 데이터도 롤백한다")
    void executeWithCompletion_rollsBackActionWhenCompletionFails() {
        IdempotencyRecord control = idempotencyService.initRecord(
                testUserId, UUID.randomUUID().toString(), "ORDER_TX_TEST");
        String actionKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotencyService.executeWithCompletion(
                control.getRecordId() + 1_000_000,
                () -> {
                    jdbcTemplate.update(
                            "INSERT INTO idempotency_records (user_id, idempotency_key, status, resource_type, created_at) "
                                    + "VALUES (?, ?, 'PROCESSING', 'ORDER_TX_TEST', NOW())",
                            testUserId, actionKey);
                    return 1L;
                },
                value -> value,
                201))
                .isInstanceOf(IllegalStateException.class);

        Integer rolledBackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, testUserId, actionKey);

        assertThat(rolledBackCount).isZero();
    }

    @Test
    @DisplayName("executeAndMarkCompleted는 COMPLETED 전환 실패 시 void action의 저장도 롤백한다")
    void executeAndMarkCompleted_rollsBackActionWhenCompletionFails() {
        IdempotencyRecord control = idempotencyService.initRecord(
                testUserId, UUID.randomUUID().toString(), "ORDER_TX_VOID_TEST");
        String actionKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotencyService.executeAndMarkCompleted(
                control.getRecordId() + 1_000_000,
                1L,
                200,
                () -> jdbcTemplate.update(
                        "INSERT INTO idempotency_records (user_id, idempotency_key, status, resource_type, created_at) "
                                + "VALUES (?, ?, 'PROCESSING', 'ORDER_TX_VOID_TEST', NOW())",
                        testUserId, actionKey)))
                .isInstanceOf(IllegalStateException.class);

        Integer rolledBackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, testUserId, actionKey);

        assertThat(rolledBackCount).isZero();
    }

    /**
     * [Phase 9] OrderController의 멱등성 흐름을 서비스 레벨에서 재현하는 헬퍼.
     *
     * <p>컨트롤러는 Spring Security, Thymeleaf 등 웹 레이어 의존성이 있어
     * 직접 호출이 어렵다. 핵심 멱등성 로직(findExisting → initRecord → createOrder → markCompleted)을
     * 동일하게 재현한다.</p>
     */
    private void executeIdempotentOrder(Long userId, String idempotencyKey, int attempt) {
        // 1단계: 기존 레코드 확인
        Optional<IdempotencyRecord> existing = idempotencyService.findExisting(userId, idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRecord prev = existing.get();
            if (prev.isCompleted() || prev.isProcessing()) {
                // 이미 처리 중이거나 완료됨 — 중복 요청 차단
                throw new com.shop.global.exception.BusinessException(
                        "IDEMPOTENCY_CONFLICT", "이미 처리된 요청입니다.");
            }
        }

        // 2단계: PROCESSING 레코드 생성 (UNIQUE 위반 시 DataIntegrityViolationException)
        IdempotencyRecord record = idempotencyService.initRecord(userId, idempotencyKey, "ORDER");

        // 3단계: 주문 생성
        try {
            OrderCreateRequest request = new OrderCreateRequest(
                    "멱등성테스트 주소",
                    "테스터" + attempt,
                    "010-0000-" + String.format("%04d", attempt),
                    "CARD",
                    BigDecimal.ZERO,
                    null, 0, null
            );
            com.shop.domain.order.entity.Order order = orderService.createOrder(userId, request);

            // 4단계: COMPLETED 전환
            idempotencyService.markCompletedForSsr(record.getRecordId(), order.getOrderId());
        } catch (Exception e) {
            // 실패 시 FAILED 전환
            idempotencyService.markFailed(record.getRecordId());
            throw e;
        }
    }
}
