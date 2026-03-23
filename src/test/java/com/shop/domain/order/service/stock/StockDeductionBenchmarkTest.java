package com.shop.domain.order.service.stock;

import com.shop.global.exception.InsufficientStockException;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감 Locking 전략 벤치마크.
 *
 * <p>V1(비관적 잠금), V2(낙관적 잠금+재시도), V3(CAS UPDATE) 세 전략을
 * Low/High 경합 시나리오에서 비교하여 처리량·레이턴시·성공률을 측정한다.</p>
 *
 * <p>벤치마크 결과는 표준 출력에 표 형태로 출력되며,
 * {@code ADR-0003-stock-deduction-locking-strategy.md}의 근거 데이터로 사용한다.</p>
 *
 * <h3>불변식 검증</h3>
 * <ul>
 *   <li>최종 재고 == 초기 재고 - 성공 차감 수 (과매도 방지)</li>
 *   <li>최종 판매량 == 초기 판매량 + 성공 차감 수</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // StockRaceConcurrencyTest와 동일한 설정으로 Spring context를 공유한다.
        // 별도 context를 생성하면 HikariPool이 추가되어 PostgreSQL max_connections를 소진할 수 있다.
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class StockDeductionBenchmarkTest {

    @Autowired private V1PessimisticLockStockDeduction v1Strategy;
    @Autowired private V2OptimisticRetryStockDeduction v2Strategy;
    @Autowired private V3CasUpdateStockDeduction v3Strategy;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private final List<BenchmarkResult> allResults = new ArrayList<>();

    // ── 벤치마크 파라미터 ──────────────────────────────────────────────

    /** 워밍업 라운드 수 (결과에 포함하지 않음) */
    private static final int WARMUP_ROUNDS = 1;

    /** 측정 라운드 수 (결과의 중앙값 사용) */
    private static final int MEASURE_ROUNDS = 3;

    // Low contention: 10 threads, 50 products, 100 stock each, 10 ops/thread = 500 total
    private static final int LOW_THREADS = 10;
    private static final int LOW_PRODUCTS = 50;
    private static final int LOW_STOCK_PER_PRODUCT = 100;
    private static final int LOW_OPS_PER_THREAD = 50;

    // High contention: 30 threads, 1 product, 300 stock, 10 ops/thread = 300 total
    private static final int HIGH_THREADS = 30;
    private static final int HIGH_PRODUCTS = 1;
    private static final int HIGH_STOCK_PER_PRODUCT = 300;
    private static final int HIGH_OPS_PER_THREAD = 10;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // ── 벤치마크 실행 ─────────────────────────────────────────────────

    @Test
    @DisplayName("Locking 전략 벤치마크: Low/High 경합 × V1/V2/V3")
    void runFullBenchmark() throws Exception {
        List<StockDeductionStrategy> strategies = List.of(v1Strategy, v2Strategy, v3Strategy);

        for (StockDeductionStrategy strategy : strategies) {
            // Warmup — Low
            for (int w = 0; w < WARMUP_ROUNDS; w++) {
                runScenario(strategy, LOW_THREADS, LOW_PRODUCTS, LOW_STOCK_PER_PRODUCT, LOW_OPS_PER_THREAD);
            }
            // Measure — Low
            List<BenchmarkResult> lowResults = new ArrayList<>();
            for (int m = 0; m < MEASURE_ROUNDS; m++) {
                lowResults.add(runScenario(strategy, LOW_THREADS, LOW_PRODUCTS,
                        LOW_STOCK_PER_PRODUCT, LOW_OPS_PER_THREAD));
            }
            allResults.add(median(lowResults, "Low"));

            // Warmup — High
            for (int w = 0; w < WARMUP_ROUNDS; w++) {
                runScenario(strategy, HIGH_THREADS, HIGH_PRODUCTS, HIGH_STOCK_PER_PRODUCT, HIGH_OPS_PER_THREAD);
            }
            // Measure — High
            List<BenchmarkResult> highResults = new ArrayList<>();
            for (int m = 0; m < MEASURE_ROUNDS; m++) {
                highResults.add(runScenario(strategy, HIGH_THREADS, HIGH_PRODUCTS,
                        HIGH_STOCK_PER_PRODUCT, HIGH_OPS_PER_THREAD));
            }
            allResults.add(median(highResults, "High"));
        }

        printResults(allResults);
    }

    // ── 단일 시나리오 실행 ────────────────────────────────────────────

    private BenchmarkResult runScenario(StockDeductionStrategy strategy,
                                        int threadCount, int productCount,
                                        int stockPerProduct, int opsPerThread)
            throws InterruptedException {

        // 상품 생성
        List<Long> productIds = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            productIds.add(fixture.createActiveProduct(stockPerProduct));
        }

        int totalOps = threadCount * opsPerThread;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        long[] latencies = new long[totalOps];
        AtomicInteger latencyIndex = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.countDown();
                    return;
                }

                for (int op = 0; op < opsPerThread; op++) {
                    // Low contention: 스레드별로 다른 상품에 접근
                    // High contention: 모든 스레드가 같은 상품에 접근
                    Long productId;
                    if (productCount == 1) {
                        productId = productIds.get(0);
                    } else {
                        productId = productIds.get((threadId * opsPerThread + op) % productCount);
                    }

                    long opStart = System.nanoTime();
                    try {
                        strategy.deductStock(List.of(
                                new StockDeductionStrategy.DeductionRequest(productId, 1)));
                        successCount.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        failCount.incrementAndGet();
                    } catch (ObjectOptimisticLockingFailureException e) {
                        // V2 재시도 소진
                        failCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
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

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

        long benchStart = System.nanoTime();
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS))
                .as("벤치마크가 120초 내에 완료되어야 합니다")
                .isTrue();
        long benchEnd = System.nanoTime();
        executor.close();

        // ── 불변식 검증 ──────────────────────────────────────

        int totalSuccess = successCount.get();
        int totalStock = productCount * stockPerProduct;

        for (Long productId : productIds) {
            Integer finalStock = jdbcTemplate.queryForObject(
                    "SELECT stock_quantity FROM products WHERE product_id = ?",
                    Integer.class, productId);
            assertThat(finalStock)
                    .as("재고는 음수가 될 수 없습니다 (상품 %d)", productId)
                    .isGreaterThanOrEqualTo(0);
        }

        Integer totalRemainingStock = jdbcTemplate.queryForObject(
                "SELECT SUM(stock_quantity) FROM products WHERE product_id IN ("
                        + String.join(",", productIds.stream().map(String::valueOf).toList()) + ")",
                Integer.class);

        assertThat(totalRemainingStock)
                .as("총 잔여 재고 == 초기 재고 - 성공 차감 수")
                .isEqualTo(totalStock - totalSuccess);

        // ── 레이턴시 계산 ────────────────────────────────────

        int recorded = Math.min(latencyIndex.get(), latencies.length);
        long[] validLatencies = Arrays.copyOf(latencies, recorded);
        Arrays.sort(validLatencies);

        double wallClockMs = (benchEnd - benchStart) / 1_000_000.0;
        double opsPerSec = totalOps / (wallClockMs / 1000.0);
        double successRate = totalOps > 0 ? (totalSuccess * 100.0 / totalOps) : 0;
        double p50 = recorded > 0 ? validLatencies[(int) (recorded * 0.50)] / 1_000_000.0 : 0;
        double p95 = recorded > 0 ? validLatencies[(int) (recorded * 0.95)] / 1_000_000.0 : 0;
        double p99 = recorded > 0 ? validLatencies[Math.min((int) (recorded * 0.99), recorded - 1)] / 1_000_000.0 : 0;

        // 테스트 데이터 정리 (다음 라운드를 위해)
        for (Long productId : productIds) {
            jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", productId);
        }

        String contention = productCount == 1 ? "High" : "Low";
        return new BenchmarkResult(
                strategy.strategyName(), contention,
                threadCount, totalOps, totalSuccess, failCount.get(),
                wallClockMs, opsPerSec, successRate,
                p50, p95, p99
        );
    }

    // ── 중앙값 선택 ──────────────────────────────────────────────────

    private BenchmarkResult median(List<BenchmarkResult> results, String contention) {
        results.sort((a, b) -> Double.compare(a.opsPerSec, b.opsPerSec));
        return results.get(results.size() / 2);
    }

    // ── 결과 출력 ────────────────────────────────────────────────────

    private void printResults(List<BenchmarkResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("=".repeat(100)).append('\n');
        sb.append("  Stock Deduction Locking Strategy Benchmark Results\n");
        sb.append("=".repeat(100)).append('\n');
        sb.append(String.format("%-16s %-11s %8s %8s %9s %10s %10s %10s%n",
                "Strategy", "Contention", "Threads", "Ops/sec", "Success%",
                "P50(ms)", "P95(ms)", "P99(ms)"));
        sb.append("-".repeat(100)).append('\n');

        for (BenchmarkResult r : results) {
            sb.append(String.format("%-16s %-11s %8d %8.0f %8.1f%% %10.2f %10.2f %10.2f%n",
                    r.strategy, r.contention, r.threadCount,
                    r.opsPerSec, r.successRate,
                    r.p50Ms, r.p95Ms, r.p99Ms));
        }

        sb.append("-".repeat(100)).append('\n');
        sb.append(String.format("  Warmup: %d round(s), Measure: %d round(s) (median selected)%n",
                WARMUP_ROUNDS, MEASURE_ROUNDS));
        sb.append("=".repeat(100)).append('\n');

        String output = sb.toString();
        System.out.println(output);

        // Gradle이 stdout을 숨길 수 있으므로 파일로도 저장
        try {
            Path reportPath = Path.of("build", "reports", "benchmark-stock-deduction.txt");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, output);
        } catch (IOException e) {
            System.err.println("벤치마크 결과 파일 저장 실패: " + e.getMessage());
        }
    }

    // ── 결과 데이터 ──────────────────────────────────────────────────

    record BenchmarkResult(
            String strategy,
            String contention,
            int threadCount,
            int totalOps,
            int successCount,
            int failCount,
            double wallClockMs,
            double opsPerSec,
            double successRate,
            double p50Ms,
            double p95Ms,
            double p99Ms
    ) {}
}
