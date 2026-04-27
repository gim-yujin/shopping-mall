package com.shop.domain.order.service.stock;

import com.shop.global.redis.StockKeyResolver;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * V1(비관 락) vs V3(DB CAS) vs V4(Redis Lua CAS) burst 비교 벤치마크.
 *
 * <p>{@link StockDeductionBurstBenchmarkTest} 와 동일한 워크로드(1상품 × {30, 100, 300}
 * 스레드)를 V4-Redis 까지 확장해 "DB 쪽 천장(~1,240 ops/sec)"이 Redis 로 얼마나
 * 올라가는지 실측한다. ADR-0003 후속 데이터.</p>
 *
 * <h3>전제</h3>
 * <ul>
 *   <li>Docker 사용 가능 — Testcontainers 가 redis:7-alpine 컨테이너 자동 기동</li>
 *   <li>{@code spring.profiles.active=redis} 활성화로 V4-Redis 빈 + StockPreloader 등록</li>
 * </ul>
 *
 * <p>결과는 {@code build/reports/benchmark-redis-vs-db.txt} 에 기록된다.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("redis")
@SuppressWarnings("PMD.CloseResource")
class RedisStockDeductionBenchmarkTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private V1PessimisticLockStockDeduction v1Strategy;
    @Autowired private V3CasUpdateStockDeduction v3Strategy;
    @Autowired private V4RedisStockDeduction v4Strategy;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private StockKeyResolver keyResolver;
    @Autowired private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private final List<BurstResult> allResults = new ArrayList<>();

    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURE_ROUNDS = 3;

    private static final int STOCK_PER_PRODUCT = 100_000;
    private static final int OPS_PER_THREAD = 5;
    private static final int[] THREAD_LEVELS = {30, 100, 300};
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
    @DisplayName("Burst-Extreme: V1(비관) vs V3(DB CAS) vs V4(Redis CAS) — 1상품 × {30,100,300}")
    void runRedisVsDbBenchmark() throws Exception {
        List<StockDeductionStrategy> strategies = List.of(v1Strategy, v3Strategy, v4Strategy);

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
        // V4 경로용 Redis 키도 함께 시드 (V1/V3 는 DB 상품 stock_quantity 사용, V4 는 Redis 사용)
        redisTemplate.opsForValue().set(
                keyResolver.productKey(productId), Integer.toString(STOCK_PER_PRODUCT));

        int totalOps = threadCount * OPS_PER_THREAD;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
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
                    } catch (RuntimeException e) {
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

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        long benchStart = System.nanoTime();
        start.countDown();
        boolean finishedInTime = done.await(ROUND_TIMEOUT_SEC, TimeUnit.SECONDS);
        long benchEnd = System.nanoTime();
        executor.close();

        // ── 불변식 검증 (해당 백엔드 기준) ──────────────────
        if (strategy instanceof V4RedisStockDeduction) {
            String remaining = redisTemplate.opsForValue().get(keyResolver.productKey(productId));
            assertThat(Integer.parseInt(remaining))
                    .as("Redis 키 잔량 == 초기 - 성공 (V4)")
                    .isEqualTo(STOCK_PER_PRODUCT - successCount.get());
        } else {
            Integer dbStock = jdbcTemplate.queryForObject(
                    "SELECT stock_quantity FROM products WHERE product_id = ?",
                    Integer.class, productId);
            assertThat(dbStock)
                    .as("DB 재고 == 초기 - 성공 (V1/V3)")
                    .isEqualTo(STOCK_PER_PRODUCT - successCount.get());
        }

        // ── 정리 (다음 라운드용) ────────────────────────────
        jdbcTemplate.update("DELETE FROM products WHERE product_id = ?", productId);
        redisTemplate.delete(keyResolver.productKey(productId));

        // ── 레이턴시 계산 ────────────────────────────────────
        int recorded = Math.min(latencyIndex.get(), latencies.length);
        long[] sortedLatencies = Arrays.copyOf(latencies, recorded);
        Arrays.sort(sortedLatencies);

        double wallClockMs = (benchEnd - benchStart) / 1_000_000.0;
        double opsPerSec = totalOps / (wallClockMs / 1000.0);
        double successRate = totalOps > 0 ? successCount.get() * 100.0 / totalOps : 0;

        return new BurstResult(
                strategy.strategyName(), threadCount, totalOps,
                successCount.get(), failCount.get(),
                wallClockMs, opsPerSec, successRate,
                percentile(sortedLatencies, 0.50),
                percentile(sortedLatencies, 0.95),
                percentile(sortedLatencies, 0.99),
                finishedInTime);
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = Math.min((int) (sorted.length * p), sorted.length - 1);
        return sorted[idx] / 1_000_000.0;
    }

    private BurstResult median(List<BurstResult> rounds) {
        rounds.sort(Comparator.comparingDouble(BurstResult::opsPerSec));
        return rounds.get(rounds.size() / 2);
    }

    // ── 결과 출력 ────────────────────────────────────────────────────

    private void printResults(List<BurstResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("=".repeat(105)).append('\n');
        sb.append("  Stock Deduction — V1(DB Pessimistic) vs V3(DB CAS) vs V4(Redis CAS)\n");
        sb.append("=".repeat(105)).append('\n');
        sb.append(String.format("%-16s %8s %9s %9s %10s %10s %10s%n",
                "Strategy", "Threads", "Ops/sec", "Success%", "P50(ms)", "P95(ms)", "P99(ms)"));
        sb.append("-".repeat(105)).append('\n');

        for (BurstResult r : results) {
            sb.append(String.format("%-16s %8d %9.0f %8.1f%% %10.2f %10.2f %10.2f%n",
                    r.strategy, r.threadCount, r.opsPerSec, r.successRate,
                    r.p50Ms, r.p95Ms, r.p99Ms));
        }

        sb.append("-".repeat(105)).append('\n');
        sb.append(String.format("  Stock per product: %d, Ops/thread: %d%n",
                STOCK_PER_PRODUCT, OPS_PER_THREAD));
        sb.append(String.format("  Redis: testcontainers redis:7-alpine, HikariCP pool: 20%n"));
        sb.append(String.format("  Warmup: %d round(s), Measure: %d round(s) (median selected)%n",
                WARMUP_ROUNDS, MEASURE_ROUNDS));
        sb.append("=".repeat(105)).append('\n');

        String output = sb.toString();
        System.out.println(output);

        try {
            Path reportPath = Path.of("build", "reports", "benchmark-redis-vs-db.txt");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, output);
        } catch (IOException e) {
            System.err.println("벤치마크 결과 파일 저장 실패: " + e.getMessage());
        }
    }

    record BurstResult(
            String strategy,
            int threadCount,
            int totalOps,
            int successCount,
            int failCount,
            double wallClockMs,
            double opsPerSec,
            double successRate,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            boolean finishedInTime
    ) {}
}
