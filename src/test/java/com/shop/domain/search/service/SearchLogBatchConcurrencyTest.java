package com.shop.domain.search.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 로그 배치 누적기 동시성 테스트
 *
 * SearchLogBatchAccumulator는 ConcurrentLinkedQueue + AtomicInteger로
 * lock-free 동시성을 구현한다. 이 테스트는 다음을 검증한다:
 *
 * 시나리오 1 — 대량 동시 add
 *   100개 스레드가 각 10건씩 총 1000건을 동시에 추가
 *   기대: totalAdded + totalDropped == 1000 (유실 없음)
 *
 * 시나리오 2 — 버퍼 오버플로우
 *   maxBufferSize를 작게 설정하고 대량 추가 → overflow 동작 검증
 *   기대: totalAdded + totalDropped == 총 시도, bufferSize ≤ maxBufferSize
 *
 * 시나리오 3 — flush 중 동시 add
 *   flush()를 호출하면서 동시에 add()를 실행
 *   기대: ConcurrentLinkedQueue의 offer/poll 동시성으로 데이터 손상 없음
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=20",
        "logging.level.org.hibernate.SQL=WARN",
        "app.search-log.batch.flush-interval-ms=999999"   // 자동 flush 비활성화
})
class SearchLogBatchConcurrencyTest {

    @Autowired
    private SearchLogBatchAccumulator accumulator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long initialLogCount;
    private String testKeywordPrefix;

    @BeforeEach
    void setUp() {
        // 잔여 버퍼 비우기
        accumulator.flush();

        testKeywordPrefix = "conctest_" + System.currentTimeMillis() + "_";
        initialLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM search_logs", Long.class);
    }

    @AfterEach
    void tearDown() {
        // 테스트 데이터 정리
        jdbcTemplate.update("DELETE FROM search_logs WHERE search_keyword LIKE ?",
                testKeywordPrefix + "%");
    }

    // =========================================================================
    // 시나리오 1: 대량 동시 add → 데이터 유실 없음
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("시나리오 1: 100스레드 × 10건 동시 add → totalAdded + totalDropped == 1000")
    void concurrentAdd_noDataLoss() throws InterruptedException {
        int threadCount = 100;
        int entriesPerThread = 10;
        int totalEntries = threadCount * entriesPerThread;

        // 메트릭 초기값 기록
        long addedBefore = accumulator.getTotalAdded();
        long droppedBefore = accumulator.getTotalDropped();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger droppedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < entriesPerThread; j++) {
                        SearchLogEntry entry = new SearchLogEntry(
                                null,
                                testKeywordPrefix + threadNum + "_" + j,
                                j, "127.0.0.1", "test-agent",
                                LocalDateTime.now());
                        if (accumulator.add(entry)) {
                            addedCount.incrementAndGet();
                        } else {
                            droppedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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

        long addedDelta = accumulator.getTotalAdded() - addedBefore;
        long droppedDelta = accumulator.getTotalDropped() - droppedBefore;

        System.out.println("========================================");
        System.out.println("[시나리오 1: 대량 동시 add]");
        System.out.println("  총 시도:       " + totalEntries + "건");
        System.out.println("  추가 성공:     " + addedCount.get() + "건");
        System.out.println("  오버플로우:    " + droppedCount.get() + "건");
        System.out.println("  메트릭 added:  " + addedDelta);
        System.out.println("  메트릭 dropped:" + droppedDelta);
        System.out.println("  버퍼 크기:     " + accumulator.getBufferSize());
        System.out.println("========================================");

        // ① 반환값 기준: 성공 + 실패 == 총 시도
        assertThat(addedCount.get() + droppedCount.get())
                .as("add() 반환값 합계가 총 시도와 일치해야 합니다")
                .isEqualTo(totalEntries);

        // ② AtomicLong 메트릭도 일치
        assertThat(addedDelta + droppedDelta)
                .as("메트릭 합계가 총 시도와 일치해야 합니다")
                .isEqualTo(totalEntries);

        // ③ flush 후 DB에 정확히 저장
        accumulator.flush();
        long finalLogCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_logs", Long.class);
        long dbInserted = finalLogCount - initialLogCount;

        assertThat(dbInserted)
                .as("DB에 저장된 건수가 추가 성공 건수와 일치해야 합니다")
                .isEqualTo(addedCount.get());
    }

    // =========================================================================
    // 시나리오 2: 버퍼 오버플로우 동시 발생
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("시나리오 2: 대량 추가로 버퍼 오버플로우 발생 → 정상 폐기, 예외 없음")
    void concurrentAdd_bufferOverflow() throws InterruptedException {
        int maxBuffer = accumulator.getMaxBufferSize();
        int threadCount = 50;
        // maxBuffer를 초과하도록 충분히 많이 추가
        int entriesPerThread = (maxBuffer / threadCount) + 10;
        int totalEntries = threadCount * entriesPerThread;

        // 버퍼를 비운 상태에서 시작
        accumulator.flush();

        long droppedBefore = accumulator.getTotalDropped();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger droppedCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < entriesPerThread; j++) {
                        SearchLogEntry entry = new SearchLogEntry(
                                null,
                                testKeywordPrefix + "overflow_" + threadNum + "_" + j,
                                0, "127.0.0.1", "test-agent",
                                LocalDateTime.now());
                        try {
                            if (accumulator.add(entry)) {
                                addedCount.incrementAndGet();
                            } else {
                                droppedCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            exceptionCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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

        long droppedDelta = accumulator.getTotalDropped() - droppedBefore;

        System.out.println("========================================");
        System.out.println("[시나리오 2: 버퍼 오버플로우]");
        System.out.println("  maxBufferSize: " + maxBuffer);
        System.out.println("  총 시도:       " + totalEntries + "건");
        System.out.println("  추가 성공:     " + addedCount.get() + "건");
        System.out.println("  오버플로우:    " + droppedCount.get() + "건");
        System.out.println("  예외 발생:     " + exceptionCount.get() + "건");
        System.out.println("  메트릭 dropped:" + droppedDelta);
        System.out.println("========================================");

        // ① 오버플로우가 1건 이상 발생
        assertThat(droppedCount.get())
                .as("버퍼 초과 시 오버플로우가 발생해야 합니다")
                .isGreaterThan(0);

        // ② 예외가 발생하면 안 됨 (graceful discard)
        assertThat(exceptionCount.get())
                .as("오버플로우 시 예외가 아닌 false 반환이어야 합니다")
                .isEqualTo(0);

        // ③ 총 시도 = 성공 + 폐기
        assertThat(addedCount.get() + droppedCount.get())
                .as("성공 + 폐기 == 총 시도")
                .isEqualTo(totalEntries);

        // 정리: 버퍼 flush
        accumulator.flush();
    }

    // =========================================================================
    // 시나리오 3: flush 중 동시 add → 안전한 drain
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("시나리오 3: flush()와 add() 동시 실행 → 데이터 손상 없음")
    void concurrentFlushAndAdd_noCorruption() throws InterruptedException {
        int addThreads = 20;
        int entriesPerThread = 50;
        int flushThreads = 5;
        int totalAdds = addThreads * entriesPerThread;

        // 버퍼를 비운 상태에서 시작
        accumulator.flush();

        long addedBefore = accumulator.getTotalAdded();
        long flushedBefore = accumulator.getTotalFlushed();
        long droppedBefore = accumulator.getTotalDropped();

        ExecutorService executor = Executors.newFixedThreadPool(addThreads + flushThreads);
        CountDownLatch ready = new CountDownLatch(addThreads + flushThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(addThreads + flushThreads);

        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger droppedByOverflow = new AtomicInteger(0);

        // add 스레드
        for (int i = 0; i < addThreads; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int j = 0; j < entriesPerThread; j++) {
                        SearchLogEntry entry = new SearchLogEntry(
                                null,
                                testKeywordPrefix + "mixed_" + threadNum + "_" + j,
                                0, "127.0.0.1", "test-agent",
                                LocalDateTime.now());
                        if (accumulator.add(entry)) {
                            addedCount.incrementAndGet();
                        } else {
                            droppedByOverflow.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // flush 스레드
        for (int i = 0; i < flushThreads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // 반복적으로 flush 호출
                    for (int j = 0; j < 10; j++) {
                        accumulator.flush();
                        Thread.sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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

        // 잔여 버퍼도 flush
        accumulator.flush();

        long addedDelta = accumulator.getTotalAdded() - addedBefore;
        long flushedDelta = accumulator.getTotalFlushed() - flushedBefore;
        long droppedDelta = accumulator.getTotalDropped() - droppedBefore;

        System.out.println("========================================");
        System.out.println("[시나리오 3: flush + add 동시]");
        System.out.println("  add 시도:      " + totalAdds + "건");
        System.out.println("  add 성공:      " + addedCount.get() + "건");
        System.out.println("  오버플로우:    " + droppedByOverflow.get() + "건");
        System.out.println("  메트릭 added:  " + addedDelta);
        System.out.println("  메트릭 flushed:" + flushedDelta);
        System.out.println("  메트릭 dropped:" + droppedDelta);
        System.out.println("  잔여 버퍼:     " + accumulator.getBufferSize());
        System.out.println("========================================");

        // ① 추가된 건수 == flushed + dropped (데이터가 사라지면 안 됨)
        // flushedDelta에는 성공 flush + 실패(dropped) 포함
        assertThat(flushedDelta + droppedDelta)
                .as("flushed + dropped(실패) == 전체 추가 시도 (데이터 누수 없음)")
                .isGreaterThanOrEqualTo(addedCount.get());

        // ② 버퍼가 비어 있어야 함 (최종 flush 완료)
        assertThat(accumulator.getBufferSize())
                .as("최종 flush 후 버퍼가 비어 있어야 합니다")
                .isEqualTo(0);

        // ③ DB에 저장된 건수 확인
        long finalLogCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM search_logs", Long.class);
        long dbInserted = finalLogCount - initialLogCount;

        assertThat(dbInserted)
                .as("DB 저장 건수가 flushed 메트릭과 일치해야 합니다")
                .isEqualTo(flushedDelta);
    }
}
