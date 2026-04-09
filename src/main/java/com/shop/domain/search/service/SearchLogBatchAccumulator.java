package com.shop.domain.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
 * 주기적으로(기본 5초) {@link SearchLogBatchWriter}를 통해
 * JDBC 배치 INSERT로 저장한다. 플러시 시 한 번에 꺼내는 최대 건수는
 * batchSize(기본 500건)이다.</p>
 *
 * <p>초당 1000건 검색 시 기존 1000회 → 2회(5초 간격)로 DB 라운드트립이 감소하고,
 * asyncExecutor 큐 점유도 0으로 줄어 다른 비동기 작업에 큐 용량을 확보한다.</p>
 *
 * <h3>WAL(Write-Ahead Log) 내구성 (Phase 20)</h3>
 * <p>프로세스 비정상 종료(kill -9, OOM Killer) 시 인메모리 버퍼의 데이터가
 * 복구 불가능하게 유실되는 문제를 해결하기 위해, {@link SearchLogWalManager}를
 * 선택적으로 연동한다. WAL이 활성화되면:</p>
 * <ul>
 *   <li>{@code add()}: 인메모리 버퍼 추가 전에 WAL 세그먼트 파일에 먼저 기록</li>
 *   <li>{@code flush()}: WAL 세그먼트를 rotate한 뒤 배치 처리 후 세그먼트 삭제</li>
 *   <li>{@code destroy()}: 잔여 버퍼 flush 후 WAL writer를 닫음</li>
 *   <li>기동 시: {@link SearchLogWalRecovery}가 잔존 세그먼트를 DB로 복원</li>
 * </ul>
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
 *   <li>{@code add()}: HTTP 스레드에서 호출. WAL 비활성 시 lock-free CAS,
 *       WAL 활성 시 WAL append(synchronized) + CAS.</li>
 *   <li>{@code scheduledFlush()}: {@code @Scheduled} 스레드에서 호출되어 주기 플러시를 담당한다.
 *       {@code flush()}는 종료 시점이나 테스트에서도 직접 호출될 수 있다.</li>
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

    // [Phase 20] WAL 관리자 — null이면 WAL 비활성 (기존 인메모리 전용 동작).
    // @Autowired(required = false)로 주입되어, WAL 빈이 없으면 null.
    private final SearchLogWalManager walManager;

    // ── 설정값 ──
    private final int batchSize;
    private final int maxBufferSize;

    // ── 메트릭 ──
    private final AtomicLong totalAdded = new AtomicLong(0);
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);

    /**
     * 테스트용 생성자 — WAL 없이 인메모리 전용으로 동작한다.
     *
     * <p>단위 테스트에서 {@code new SearchLogBatchAccumulator(mockWriter, 3, 5)}로
     * 직접 생성할 때 사용한다. WAL 관리자는 null로 설정되어 기존 동작과 동일하다.</p>
     *
     * @param writer        JDBC 배치 INSERT 실행기
     * @param batchSize     한 번의 플러시에서 처리할 최대 건수
     * @param maxBufferSize 버퍼 최대 크기 — 초과 시 새 로그 폐기
     */
    public SearchLogBatchAccumulator(SearchLogBatchWriter writer, int batchSize, int maxBufferSize) {
        this(writer, batchSize, maxBufferSize, null);
    }

    /**
     * Spring 컨텍스트용 생성자 — WAL 관리자를 선택적으로 주입받는다.
     *
     * <p>{@code SearchLogWalManager} 빈이 존재하면(WAL 활성) 주입되고,
     * 존재하지 않으면(WAL 비활성) null이 주입되어 기존 인메모리 전용으로 동작한다.
     * {@code @ConditionalOnProperty}로 WAL 빈 생성을 제어하므로,
     * 설정 한 줄로 WAL 활성/비활성을 전환할 수 있다.</p>
     *
     * @param writer        JDBC 배치 INSERT 실행기
     * @param batchSize     한 번의 플러시에서 처리할 최대 건수 (기본 500)
     * @param maxBufferSize 버퍼 최대 크기 — 초과 시 새 로그 폐기 (기본 10,000)
     * @param walManager    WAL 관리자 (null이면 WAL 비활성)
     */
    @Autowired
    public SearchLogBatchAccumulator(
            SearchLogBatchWriter writer,
            @Value("${app.search-log.batch.batch-size:500}") int batchSize,
            @Value("${app.search-log.batch.max-buffer-size:10000}") int maxBufferSize,
            @Autowired(required = false) SearchLogWalManager walManager) {
        this.writer = writer;
        this.batchSize = batchSize;
        this.maxBufferSize = maxBufferSize;
        this.walManager = walManager;

        if (walManager != null) {
            log.info("검색 로그 WAL 활성화 — dir={}", walManager.getWalDir());
        }
    }

    /**
     * 검색 로그를 버퍼에 추가한다.
     *
     * <p>HTTP 스레드에서 호출되며, 다음 순서로 처리한다:</p>
     * <ol>
     *   <li>버퍼 오버플로우 검사 — 최대 크기 초과 시 즉시 폐기</li>
     *   <li>[Phase 20] WAL 기록 — 디스크에 먼저 기록하여 크래시 내구성 확보</li>
     *   <li>인메모리 버퍼 추가 — lock-free CAS로 즉시 반환</li>
     * </ol>
     *
     * <p>WAL 기록이 실패해도 인메모리 버퍼에는 추가된다.
     * 정상 운영 중에는 DB flush로 저장되며, WAL 실패 + 프로세스 크래시가
     * 동시에 발생하는 극단적 상황에서만 해당 엔트리가 유실된다.</p>
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

        // [Phase 20] WAL 기록: 프로세스 비정상 종료 시 복구할 수 있도록
        // 인메모리 버퍼 추가 전에 디스크에 먼저 기록한다.
        // WAL이 비활성(walManager == null)이면 기존 인메모리 전용 동작.
        if (walManager != null) {
            walManager.append(entry);
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
     * <p>[Phase 20] WAL 활성 시 플러시 흐름:</p>
     * <ol>
     *   <li>WAL 세그먼트 rotate — 현재 세그먼트를 닫고 새 세그먼트를 연다.
     *       이후 add()는 새 세그먼트에 기록된다.</li>
     *   <li>인메모리 버퍼 drain — 엔트리를 배치 단위로 꺼내 DB에 저장한다.</li>
     *   <li>닫힌 세그먼트 삭제 — 배치 처리가 완료되면 세그먼트를 삭제한다.
     *       이 시점에는 엔트리가 DB에 저장되었거나, 저장 실패 시 의도적으로 폐기된 상태다.</li>
     * </ol>
     *
     * <p>세그먼트 rotate와 삭제 사이에 크래시가 발생하면, 다음 기동 시
     * {@link SearchLogWalRecovery}가 해당 세그먼트를 복구한다.
     * 이미 DB에 저장된 엔트리가 중복 INSERT될 수 있지만,
     * 검색 로그는 통계 목적이므로 소수의 중복은 허용된다.</p>
     */
    public void flush() {
        // [Phase 20] WAL 세그먼트 rotate — 현재 세그먼트를 봉인하고 새 세그먼트로 전환.
        // rotate 후 add()는 새 세그먼트에 기록되므로, 닫힌 세그먼트에는 더 이상 쓰기 없음.
        // 세그먼트가 비어있으면 null을 반환하고 자동 삭제된다.
        Path oldSegment = (walManager != null) ? walManager.rotateSegment() : null;

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

        // [Phase 20] 봉인된 WAL 세그먼트 삭제.
        // 모든 배치가 처리(저장 또는 폐기)된 후 삭제하여, 프로세스 크래시 시
        // WAL에 남아 있는 엔트리가 다음 기동 시 복구되도록 보장한다.
        if (walManager != null && oldSegment != null) {
            walManager.deleteSegment(oldSegment);
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
     * Graceful Shutdown: 애플리케이션 종료 시 버퍼에 남은 로그를 모두 플러시한다.
     *
     * <p>Spring의 {@code DisposableBean} 콜백으로 실행되어
     * 배포/재시작 시 인메모리 버퍼의 데이터 유실을 최소화한다.
     * 기존 asyncExecutor의 {@code waitForTasksToCompleteOnShutdown}과 유사한 역할.</p>
     *
     * <p>[Phase 20] WAL 활성 시 flush 후 WAL writer를 닫는다.
     * flush()가 WAL 세그먼트를 rotate + 삭제하므로, close() 시점의 현재 세그먼트는
     * 빈 상태이거나 이미 처리된 상태이다.</p>
     */
    @Override
    public void destroy() {
        int remaining = bufferSize.get();
        if (remaining > 0) {
            log.info("Graceful Shutdown — 버퍼 잔여 검색 로그 플러시 시작: remaining={}", remaining);
            flush();
            log.info("Graceful Shutdown — 검색 로그 플러시 완료: flushed={}, dropped={}",
                    totalFlushed.get(), totalDropped.get());
        }

        // [Phase 20] WAL writer 정리 — BufferedWriter를 닫아 OS 리소스를 해제한다.
        if (walManager != null) {
            walManager.close();
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

    /** [Phase 20] WAL 관리자 (null이면 WAL 비활성) */
    SearchLogWalManager getWalManager() {
        return walManager;
    }
}
