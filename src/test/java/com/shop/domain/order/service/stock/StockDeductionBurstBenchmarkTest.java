package com.shop.domain.order.service.stock;

import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감 Locking 전략 — burst-extreme 벤치마크.
 *
 * <p>{@link StockDeductionBenchmarkTest}는 Low(10t/50p)·High(30t/1p)까지만 측정한다.
 * 본 테스트는 동일 상품 1개에 30 → 100 → 300 스레드 burst를 가해
 * V1(비관적 락)이 어디서 무너지고 V3(CAS)가 어떻게 거동하는지를 측정한다.</p>
 *
 * <h3>관찰 항목</h3>
 * <ul>
 *   <li><b>Lock timeout</b>: PostgreSQL {@code lock_timeout}으로 row-lock 대기 중단</li>
 *   <li><b>Pool timeout</b>: HikariCP {@code connection-timeout}으로 풀 대기 중단</li>
 *   <li>처리량(ops/sec)·성공률·P50/P95/P99 레이턴시</li>
 * </ul>
 *
 * <h3>불변식</h3>
 * <ul>
 *   <li>최종 재고 == 초기 재고 − 성공 차감 수 (과매도 0건)</li>
 * </ul>
 *
 * <p>결과는 {@code build/reports/benchmark-stock-deduction-burst.txt}에 기록되며
 * {@code ADR-0003} "한계 조건(Limits)" 섹션의 근거 데이터로 사용한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 기본 벤치마크와 동일한 풀 크기. 30→300 스레드는 풀(20)을 의도적으로 초과시켜
        // pool-wait가 결과에 어떻게 반영되는지 관찰하기 위함이다.
        "spring.datasource.hikari.maximum-pool-size=20",
        // burst에서 실패 신호를 빨리 받기 위해 lock_timeout을 3초로 단축.
        // (운영은 5초, test 기본은 10초)
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '3s'",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class StockDeductionBurstBenchmarkTest {

    @Autowired private V1PessimisticLockStockDeduction v1Strategy;
    @Autowired private V3CasUpdateStockDeduction v3Strategy;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private final List<BurstResult> allResults = new ArrayList<>();

    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURE_ROUNDS = 3;

    /** 락 경합이 한계가 되도록 재고를 충분히 크게 — 재고 부족으로 실패하지 않게. */
    private static final int STOCK_PER_PRODUCT = 100_000;
    private static final int OPS_PER_THREAD = 5;

    /** 30 = 풀(20) 약간 초과, 100 = 5배, 300 = 15배. 곡선의 형태를 보기 위해 3개 포인트. */
    private static final int[] THREAD_LEVELS = {30, 100, 300};

    /** 단일 라운드 wall-clock 상한. V1 300스레드는 직렬화로 길어질 수 있어 여유 부여. */
    private static final long ROUND_TIMEOUT_SEC = 600;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("Burst-Extreme: V1(비관) vs V3(CAS) — 1상품 × {30,100,300} 스레드")
    void runBurstBenchmark() throws Exception {
        List<StockDeductionStrategy> strategies = List.of(v1Strategy, v3Strategy);

        for (StockDeductionStrategy strategy : strategies) {
            for (int threadCount : THREAD_LEVELS) {
                for (int w = 0; w < WARMUP_ROUNDS; w++) {
                    runScenario(strategy, threadCount);
                }
                List<BurstResult> rounds = new ArrayList<>();
                for (int m = 0; m < MEASURE_ROUNDS; m++) {
                    rounds.add(runScenario(strategy, threadCount));
                }
                allResults.add(median(rounds));
            }
        }

        printResults(allResults);
    }

    // ── 단일 시나리오 실행 ────────────────────────────────────────────

    private BurstResult runScenario(StockDeductionStrategy strategy, int threadCount)
            throws InterruptedException {

        Long productId = fixture.createActiveProduct(STOCK_PER_PRODUCT);
        int totalOps = threadCount * OPS_PER_THREAD;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger lockTimeoutCount = new AtomicInteger();
        AtomicInteger poolTimeoutCount = new AtomicInteger();
        AtomicInteger otherFailCount = new AtomicInteger();
        long[] latencies = new long[totalOps];
        AtomicInteger latencyIndex = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.countDown();
                    return;
                }

                for (int op = 0; op < OPS_PER_THREAD; op++) {
                    long opStart = System.nanoTime();
                    try {
                        strategy.deductStock(List.of(
                                new StockDeductionStrategy.DeductionRequest(productId, 1)));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        if (isLockTimeout(e)) {
                            lockTimeoutCount.incrementAndGet();
                        } else if (isPoolTimeout(e)) {
                            poolTimeoutCount.incrementAndGet();
                        } else {
                            otherFailCount.incrementAndGet();
                        }
                    }
                    long opEnd = System.nanoTime();
                    int idx = latencyIndex.getAndIncrement();
                    if (idx < latencies.length) {
                        latencies[idx] = opEnd - opStart;
                    }
                }
                done.countDown();
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS))
                .as("모든 스레드가 30초 내에 준비되어야 합니다")
                .isTrue();

        long benchStart = System.nanoTime();
        start.countDown();
        boolean finishedInTime = done.await(ROUND_TIMEOUT_SEC, TimeUnit.SECONDS);
        long benchEnd = System.nanoTime();
        executor.close();

        // ── 불변식 검증 ──────────────────────────────────────
        Integer finalStock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, productId);
        assertThat(finalStock)
                .as("최종 재고 == 초기 재고 - 성공 차감 수 (과매도 0건)")
                .isEqualTo(STOCK_PER_PRODUCT - successCount.get());

        // ── 다음 라운드를 위한 정리 ──────────────────────────
        jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", productId);

        // ── 레이턴시 계산 ────────────────────────────────────
        int recorded = Math.min(latencyIndex.get(), latencies.length);
        long[] sortedLatencies = Arrays.copyOf(latencies, recorded);
        Arrays.sort(sortedLatencies);

        double wallClockMs = (benchEnd - benchStart) / 1_000_000.0;
        double opsPerSec = totalOps / (wallClockMs / 1000.0);
        double successRate = totalOps > 0 ? successCount.get() * 100.0 / totalOps : 0;

        return new BurstResult(
                strategy.strategyName(), threadCount, totalOps, successCount.get(),
                lockTimeoutCount.get(), poolTimeoutCount.get(), otherFailCount.get(),
                wallClockMs, opsPerSec, successRate,
                percentile(sortedLatencies, 0.50),
                percentile(sortedLatencies, 0.95),
                percentile(sortedLatencies, 0.99),
                finishedInTime
        );
    }

    private static double percentile(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = Math.min((int) (sortedNanos.length * p), sortedNanos.length - 1);
        return sortedNanos[idx] / 1_000_000.0;
    }

    private static boolean isLockTimeout(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof CannotAcquireLockException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("lock timeout") || lower.contains("55p03")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private static boolean isPoolTimeout(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("connection is not available")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private BurstResult median(List<BurstResult> rounds) {
        rounds.sort(Comparator.comparingDouble(BurstResult::opsPerSec));
        return rounds.get(rounds.size() / 2);
    }

    // ── 결과 출력 ────────────────────────────────────────────────────

    private void printResults(List<BurstResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("=".repeat(115)).append('\n');
        sb.append("  Stock Deduction Locking Strategy — Burst-Extreme Benchmark\n");
        sb.append("=".repeat(115)).append('\n');
        sb.append(String.format("%-16s %8s %8s %9s %9s %9s %10s %10s %10s%n",
                "Strategy", "Threads", "Ops/sec", "Success%",
                "LockT/O", "PoolT/O", "P50(ms)", "P95(ms)", "P99(ms)"));
        sb.append("-".repeat(115)).append('\n');

        for (BurstResult r : results) {
            sb.append(String.format("%-16s %8d %8.0f %8.1f%% %9d %9d %10.2f %10.2f %10.2f%n",
                    r.strategy, r.threadCount, r.opsPerSec, r.successRate,
                    r.lockTimeouts, r.poolTimeouts,
                    r.p50Ms, r.p95Ms, r.p99Ms));
        }

        sb.append("-".repeat(115)).append('\n');
        sb.append(String.format("  Stock per product: %d, Ops/thread: %d%n",
                STOCK_PER_PRODUCT, OPS_PER_THREAD));
        sb.append(String.format("  HikariCP pool: 20, lock_timeout: 3s%n"));
        sb.append(String.format("  Warmup: %d round(s), Measure: %d round(s) (median selected)%n",
                WARMUP_ROUNDS, MEASURE_ROUNDS));
        sb.append("=".repeat(115)).append('\n');

        String output = sb.toString();
        System.out.println(output);

        try {
            Path reportPath = Path.of("build", "reports", "benchmark-stock-deduction-burst.txt");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, output);
        } catch (IOException e) {
            System.err.println("벤치마크 결과 파일 저장 실패: " + e.getMessage());
        }
    }

    // ── 결과 데이터 ──────────────────────────────────────────────────

    record BurstResult(
            String strategy,
            int threadCount,
            int totalOps,
            int successCount,
            int lockTimeouts,
            int poolTimeouts,
            int otherFails,
            double wallClockMs,
            double opsPerSec,
            double successRate,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            boolean finishedInTime
    ) {}
}
