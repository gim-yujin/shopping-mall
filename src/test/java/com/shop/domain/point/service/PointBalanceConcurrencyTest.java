package com.shop.domain.point.service;

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
 * 포인트 잔액 동시성 테스트
 *
 * User.addPoints() / usePoints()는 엔티티 레벨 메서드로
 * 별도 동기화 없이 pointBalance를 직접 변경한다.
 * 주문/취소 서비스에서 findByIdWithLockAndTier()로 비관적 잠금을 잡고 호출하기 때문에
 * 실 운영 경로에서는 안전하지만, 잠금 없이 호출하면 다음 문제가 발생할 수 있다:
 *
 * 시나리오 1 — 동시 포인트 사용 (잔액 음수 방지)
 *   1000P 보유 사용자에게 10개 스레드가 각 200P씩 동시 사용 요청
 *   비관적 잠금이 없으면: check-then-act 레이스로 잔액이 음수 가능
 *   비관적 잠금이 있으면: 최대 5건만 성공, 나머지는 IllegalArgumentException
 *
 * 시나리오 2 — 동시 포인트 적립 (Lost Update 방지)
 *   0P 사용자에게 20개 스레드가 각 100P씩 동시 적립
 *   비관적 잠금이 없으면: Lost Update로 최종 잔액 < 2000P
 *   비관적 잠금이 있으면: 최종 잔액 정확히 2000P
 *
 * 시나리오 3 — 적립 + 사용 혼합 동시 실행
 *   1000P 보유 사용자에게 적립(+100) 10건 + 사용(-100) 10건 동시 실행
 *   비관적 잠금이 있으면: 최종 잔액 == 초기 잔액(1000P)
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
class PointBalanceConcurrencyTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PointConcurrencyHelper helper;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE is_active = true AND role = 'ROLE_USER' ORDER BY user_id LIMIT 1",
                Long.class);
    }

    private void setPointBalance(int balance) {
        jdbcTemplate.update("UPDATE users SET point_balance = ?, updated_at = NOW() WHERE user_id = ?",
                balance, testUserId);
    }

    private int getPointBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?", Integer.class, testUserId);
    }

    // =========================================================================
    // 시나리오 1: 동시 포인트 사용 → 잔액 음수 방지
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 1000P 보유, 10스레드 × 200P 사용 → 최대 5건 성공, 잔액 ≥ 0")
    void concurrentUsePoints_preventsNegativeBalance() throws InterruptedException {
        int initialBalance = 1000;
        int useAmount = 200;
        int threadCount = 10;
        int maxExpectedSuccess = initialBalance / useAmount; // 5

        setPointBalance(initialBalance);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    helper.usePointsWithLock(testUserId, useAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("부족")) {
                        insufficientCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalBalance = getPointBalance();

        System.out.println("========================================");
        System.out.println("[시나리오 1: 동시 포인트 사용]");
        System.out.println("  초기 잔액:       " + initialBalance + "P");
        System.out.println("  사용 단위:       " + useAmount + "P × " + threadCount + "스레드");
        System.out.println("  성공:            " + successCount.get() + "건");
        System.out.println("  잔액 부족 실패:  " + insufficientCount.get() + "건");
        System.out.println("  기타 실패:       " + otherFailCount.get() + "건");
        System.out.println("  최종 잔액:       " + finalBalance + "P");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 잔액은 절대 음수가 되면 안 된다
        assertThat(finalBalance)
                .as("포인트 잔액이 음수가 되면 안 됩니다 (현재: %dP)", finalBalance)
                .isGreaterThanOrEqualTo(0);

        // ② 성공 건수 × 사용금액 == 초기잔액 - 최종잔액
        assertThat(successCount.get() * useAmount)
                .as("성공 건수 × 사용금액 == 차감된 총액")
                .isEqualTo(initialBalance - finalBalance);

        // ③ 최대 성공 건수를 초과하면 안 된다
        assertThat(successCount.get())
                .as("최대 %d건까지만 성공해야 합니다", maxExpectedSuccess)
                .isLessThanOrEqualTo(maxExpectedSuccess);

        // ④ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);

        // ⑤ 모든 스레드가 성공 또는 잔액부족으로 종료
        assertThat(successCount.get() + insufficientCount.get())
                .as("성공 + 잔액부족 = 전체 스레드")
                .isEqualTo(threadCount);
    }

    // =========================================================================
    // 시나리오 2: 동시 포인트 적립 → Lost Update 방지
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 0P 보유, 20스레드 × 100P 적립 → 최종 잔액 정확히 2000P")
    void concurrentAddPoints_preventsLostUpdate() throws InterruptedException {
        int initialBalance = 0;
        int addAmount = 100;
        int threadCount = 20;
        int expectedFinal = initialBalance + (addAmount * threadCount); // 2000

        setPointBalance(initialBalance);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    helper.addPointsWithLock(testUserId, addAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                    errors.add("시도#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalBalance = getPointBalance();

        System.out.println("========================================");
        System.out.println("[시나리오 2: 동시 포인트 적립]");
        System.out.println("  초기 잔액:     " + initialBalance + "P");
        System.out.println("  적립 단위:     " + addAmount + "P × " + threadCount + "스레드");
        System.out.println("  성공:          " + successCount.get() + "건");
        System.out.println("  기타 실패:     " + otherFailCount.get() + "건");
        System.out.println("  최종 잔액:     " + finalBalance + "P (기대: " + expectedFinal + "P)");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 모든 적립이 성공
        assertThat(successCount.get())
                .as("모든 적립이 성공해야 합니다")
                .isEqualTo(threadCount);

        // ② 최종 잔액이 정확히 일치 (Lost Update 없음)
        assertThat(finalBalance)
                .as("Lost Update가 발생하면 잔액이 %dP보다 적습니다 (현재: %dP)", expectedFinal, finalBalance)
                .isEqualTo(expectedFinal);

        // ③ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // 시나리오 3: 적립 + 사용 혼합 → 정합성 검증
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: 1000P 보유, 적립(+100) 10건 + 사용(-100) 10건 → 최종 잔액 1000P")
    void concurrentMixedOperations_maintainsConsistency() throws InterruptedException {
        int initialBalance = 1000;
        int amount = 100;
        int addThreads = 10;
        int useThreads = 10;
        int totalThreads = addThreads + useThreads;

        setPointBalance(initialBalance);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalThreads);

        AtomicInteger addSuccess = new AtomicInteger(0);
        AtomicInteger useSuccess = new AtomicInteger(0);
        AtomicInteger useFailCount = new AtomicInteger(0);
        AtomicInteger otherFailCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // 적립 스레드
        for (int i = 0; i < addThreads; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    helper.addPointsWithLock(testUserId, amount);
                    addSuccess.incrementAndGet();
                } catch (Exception e) {
                    otherFailCount.incrementAndGet();
                    errors.add("적립#" + attempt + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        // 사용 스레드
        for (int i = 0; i < useThreads; i++) {
            final int attempt = i + 1;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    helper.usePointsWithLock(testUserId, amount);
                    useSuccess.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("부족")) {
                        useFailCount.incrementAndGet();
                    } else {
                        otherFailCount.incrementAndGet();
                        errors.add("사용#" + attempt + ": " + e.getClass().getSimpleName() + " - " + msg);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 작업 완료").isTrue();
        } finally {
            executor.close();
        }

        int finalBalance = getPointBalance();
        int expectedBalance = initialBalance + (addSuccess.get() * amount) - (useSuccess.get() * amount);

        System.out.println("========================================");
        System.out.println("[시나리오 3: 적립 + 사용 혼합]");
        System.out.println("  초기 잔액:       " + initialBalance + "P");
        System.out.println("  적립 성공:       " + addSuccess.get() + "건 (+" + addSuccess.get() * amount + "P)");
        System.out.println("  사용 성공:       " + useSuccess.get() + "건 (-" + useSuccess.get() * amount + "P)");
        System.out.println("  잔액 부족 실패:  " + useFailCount.get() + "건");
        System.out.println("  기타 실패:       " + otherFailCount.get() + "건");
        System.out.println("  최종 잔액:       " + finalBalance + "P (기대: " + expectedBalance + "P)");
        if (!errors.isEmpty()) {
            errors.forEach(e -> System.out.println("    → " + e));
        }
        System.out.println("========================================");

        // ① 잔액이 음수가 아님
        assertThat(finalBalance)
                .as("포인트 잔액이 음수가 되면 안 됩니다")
                .isGreaterThanOrEqualTo(0);

        // ② 최종 잔액 == 초기 + 적립 성공분 - 사용 성공분
        assertThat(finalBalance)
                .as("최종 잔액이 연산 결과와 일치해야 합니다")
                .isEqualTo(expectedBalance);

        // ③ 적립은 모두 성공
        assertThat(addSuccess.get())
                .as("적립은 모두 성공해야 합니다")
                .isEqualTo(addThreads);

        // ④ 예상치 못한 에러 없음
        assertThat(otherFailCount.get())
                .as("예상치 못한 예외: %s", errors)
                .isEqualTo(0);
    }

    // =========================================================================
    // tearDown: 테스트 후 포인트 원복
    // =========================================================================

    @AfterEach
    void tearDown() {
        if (testUserId != null) {
            jdbcTemplate.update("UPDATE users SET point_balance = 0, updated_at = NOW() WHERE user_id = ?",
                    testUserId);
        }
    }
}
