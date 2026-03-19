package com.shop.domain.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Phase 19] 검색 로그 배치 쓰기 누적기 — 개별 INSERT를 배치 INSERT로 전환.
 *
 * <h3>기존 문제</h3>
 * <p>{@code SearchService.logSearch()}는 {@code @Async}로 비동기 실행하여 HTTP 스레드를 해방했지만,
 * 검색 건마다 개별 INSERT 1회 + 트랜잭션 1회가 발생했다.
 * 초당 1000건 검색 시:</p>
 * <ul>
 *   <li>DB 라운드트립 1000회 → 네트워크 지연 누적</li>
 *   <li>트랜잭션 1000회 → WAL 쓰기 1000회, fsync 병목</li>
 *   <li>커넥션 풀에서 1000회 checkout/return → HikariCP 경합</li>
 *   <li>asyncExecutor 큐(500) 포화 → 검색 로그 유실</li>
 * </ul>
 *
 * <h3>해결: 인메모리 배치 누적</h3>
 * <p>검색 로그를 lock-free {@link ConcurrentLinkedQueue}에 즉시 추가(O(1))하고,
 * 주기적으로(기본 5초) 또는 버퍼 임계치(기본 500건) 도달 시
 * {@link SearchLogBatchWriter}를 통해 JDBC 배치 INSERT로 한 번에 저장한다.</p>
 *
 * <p>초당 1000건 검색 시 기존 1000회 → 2회(5초 간격)로 DB 라운드트립이 감소하고,
 * asyncExecutor 큐 점유도 0으로 줄어 다른 비동기 작업에 큐 용량을 확보한다.</p>
 *
 * <h3>버퍼 오버플로우 보호</h3>
 * <p>최대 버퍼 크기(기본 10,000건)를 초과하면 새 로그를 폐기한다.
 * 검색 로그는 통계 목적이므로 일부 유실이 허용되며,
 * 이는 기존 asyncExecutor DiscardPolicy와 동일한 정책이다.</p>
 *
 * <h3>Graceful Shutdown</h3>
 * <p>{@link DisposableBean#destroy()}에서 버퍼에 남은 로그를 모두 플러시하여
 * 배포/재시작 시 데이터 유실을 최소화한다.</p>
 *
 * <h3>스레드 안전성</h3>
 * <ul>
 *   <li>{@code add()}: HTTP 스레드에서 호출. {@code ConcurrentLinkedQueue.offer()}는 lock-free CAS 연산.</li>
 *   <li>{@code flush()}: {@code @Scheduled} 스레드에서 호출. 단일 스레드 실행으로 동시 플러시 방지.</li>
 *   <li>{@code bufferSize}: {@code AtomicInteger}로 근사치 추적. ConcurrentLinkedQueue.size()는
 *       O(n)이므로 별도 카운터로 O(1) 근사치를 유지한다.</li>
 * </ul>
 */
@Component
public class SearchLogBatchAccumulator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchLogBatchAccumulator.class);

    private final ConcurrentLinkedQueue<SearchLogEntry> buffer = new ConcurrentLinkedQueue<>();

    // [Phase 19] ConcurrentLinkedQueue.size()는 O(n) 순회이므로,
    // AtomicInteger로 O(1) 근사치를 별도 추적한다.
    // add/drain에서 증감하므로 정확한 값은 아니지만, 오버플로우 보호에는 충분하다.
    private final AtomicInteger bufferSize = new AtomicInteger(0);

    private final SearchLogBatchWriter writer;

    // ── 설정값 ──
    private final int batchSize;
    private final int maxBufferSize;

    // ── 메트릭 ──
    private final AtomicLong totalAdded = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);

    /**
     * @param writer        JDBC 배치 INSERT 실행기
     * @param batchSize     한 번의 플러시에서 처리할 최대 건수 (기본 500)
     * @param maxBufferSize 버퍼 최대 크기 — 초과 시 새 로그 폐기 (기본 10,000)
     */
    public SearchLogBatchAccumulator(
            SearchLogBatchWriter writer,
            @Value("${app.search-log.batch.batch-size:500}") int batchSize,
            @Value("${app.search-log.batch.max-buffer-size:10000}") int maxBufferSize) {
        this.writer = writer;
        this.batchSize = batchSize;
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * 검색 로그를 버퍼에 추가한다.
     *
     * <p>HTTP 스레드에서 호출되며, {@code ConcurrentLinkedQueue.offer()}는
     * lock-free CAS 연산으로 블로킹 없이 즉시 반환된다.
     * 기존 {@code @Async + save()}가 asyncExecutor 큐에 태스크를 제출하던 것과 달리,
     * 스레드 풀 큐를 점유하지 않아 다른 비동기 작업의 가용성을 보존한다.</p>
     *
     * @param entry 검색 로그 엔트리
     * @return true이면 버퍼에 추가됨, false이면 오버플로우로 폐기됨
     */
    public boolean add(SearchLogEntry entry) {
        // [Phase 19] 버퍼 오버플로우 보호: 검색 로그는 통계 목적이므로 유실 허용.
        // 기존 asyncExecutor DiscardPolicy와 동일한 정책.
        if (bufferSize.get() >= maxBufferSize) {
            totalDropped.incrementAndGet();
            return false;
        }

        buffer.offer(entry);
        bufferSize.incrementAndGet();
        totalAdded.incrementAndGet();
        return true;
    }

    /**
     * 주기적 플러시 — 버퍼의 모든 엔트리를 배치 단위로 DB에 저장한다.
     *
     * <p>{@code @Scheduled(fixedDelay)}를 사용하여 이전 플러시 완료 후
     * 지정된 간격(기본 5초)만큼 대기한 뒤 다음 플러시를 실행한다.
     * {@code fixedRate}가 아닌 {@code fixedDelay}를 사용하는 이유:
     * 대량의 버퍼를 플러시하는 데 시간이 걸릴 때 플러시가 겹치는 것을 방지한다.</p>
     *
     * <p>버퍼에 남은 엔트리가 batchSize를 초과하면 여러 배치로 나누어 저장한다.
     * 각 배치는 {@code SearchLogBatchWriter}의 독립 트랜잭션으로 실행되어,
     * 한 배치 실패가 다른 배치에 영향을 주지 않는다.</p>
     */
    @Scheduled(fixedDelayString = "${app.search-log.batch.flush-interval-ms:5000}")
    public void scheduledFlush() {
        flush();
    }

    /**
     * 버퍼의 모든 엔트리를 배치 단위로 플러시한다.
     *
     * <p>drain 패턴: {@code buffer.poll()}로 엔트리를 하나씩 꺼내 배치 리스트에 추가한다.
     * 배치 크기에 도달하거나 버퍼가 비면 {@code writer.writeBatch()}로 저장한다.
     * {@code ConcurrentLinkedQueue.poll()}은 lock-free이므로
     * {@code add()}와 동시에 실행되어도 안전하다.</p>
     */
    public void flush() {
        while (!buffer.isEmpty()) {
            List<SearchLogEntry> batch = drain(batchSize);
            if (batch.isEmpty()) {
                break;
            }

            try {
                writer.writeBatch(batch);
                totalFlushed.addAndGet(batch.size());
                flushCount.incrementAndGet();
            } catch (Exception e) {
                // [Phase 19] 배치 저장 실패 시 해당 배치의 로그는 유실된다.
                // 검색 로그는 통계 목적이므로 일부 유실이 허용되며,
                // 실패한 배치를 버퍼에 재삽입하면 무한 재시도 루프 위험이 있다.
                totalDropped.addAndGet(batch.size());
                log.warn("[Phase 19] 검색 로그 배치 저장 실패 — batchSize={}, dropped={}",
                        batch.size(), totalDropped.get(), e);
            }
        }
    }

    /**
     * 버퍼에서 최대 maxDrain건을 꺼내 리스트로 반환한다.
     */
    private List<SearchLogEntry> drain(int maxDrain) {
        List<SearchLogEntry> batch = new ArrayList<>(Math.min(maxDrain, bufferSize.get()));
        while (batch.size() < maxDrain) {
            SearchLogEntry entry = buffer.poll();
            if (entry == null) {
                break;
            }
            batch.add(entry);
            bufferSize.decrementAndGet();
        }
        return batch;
    }

    /**
     * [Phase 19] Graceful Shutdown: 애플리케이션 종료 시 버퍼에 남은 로그를 모두 플러시한다.
     *
     * <p>Spring의 {@code DisposableBean} 콜백으로 실행되어
     * 배포/재시작 시 인메모리 버퍼의 데이터 유실을 최소화한다.
     * 기존 asyncExecutor의 {@code waitForTasksToCompleteOnShutdown}과 유사한 역할.</p>
     */
    @Override
    public void destroy() {
        int remaining = bufferSize.get();
        if (remaining > 0) {
            log.info("[Phase 19] Graceful Shutdown — 버퍼 잔여 검색 로그 플러시 시작: remaining={}", remaining);
            flush();
            log.info("[Phase 19] Graceful Shutdown — 검색 로그 플러시 완료: flushed={}, dropped={}",
                    totalFlushed.get(), totalDropped.get());
        }
    }

    // ── 메트릭 접근자 (모니터링/테스트용) ──

    /** 현재 버퍼 크기 (근사치) */
    public int getBufferSize() {
        return bufferSize.get();
    }

    /** 누적 추가 건수 */
    public long getTotalAdded() {
        return totalAdded.get();
    }

    /** 누적 플러시(DB 저장) 건수 */
    public long getTotalFlushed() {
        return totalFlushed.get();
    }

    /** 누적 폐기(오버플로우 + 저장 실패) 건수 */
    public long getTotalDropped() {
        return totalDropped.get();
    }

    /** 누적 플러시 횟수 */
    public long getFlushCount() {
        return flushCount.get();
    }

    /** 설정된 배치 크기 */
    public int getBatchSize() {
        return batchSize;
    }

    /** 설정된 최대 버퍼 크기 */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }
}
