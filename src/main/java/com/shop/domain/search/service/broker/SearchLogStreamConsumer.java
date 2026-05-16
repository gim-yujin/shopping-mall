package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogBatchWriter;
import com.shop.domain.search.service.SearchLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Phase 21] Redis Streams 컨슈머. {@link SearchLogStreamProducer} 가 적재한 메시지를
 * 읽어 {@link SearchLogBatchWriter} 로 DB INSERT 한다.
 *
 * <h3>[Phase 22-1] 배치 INSERT 복원</h3>
 * <p>Phase 21 초기 구현은 {@code StreamListener.onMessage} 단건 콜백마다 INSERT 1회 + XACK 1회를
 * 수행해 Phase 19 의 JDBC 배치 INSERT 이점을 잃었다. Phase 22-1 부터는 콜백을 받으면
 * 인메모리 버퍼에 누적만 하고, 다음 두 조건 중 하나에서 flush 한다:</p>
 * <ol>
 *   <li>버퍼가 {@code dbBatchSize} 에 도달</li>
 *   <li>{@code batchFlushInterval} 주기 스케줄러가 깨어남</li>
 * </ol>
 * <p>flush 는 단일 트랜잭션 배치 INSERT + 배치 XACK 으로 N 건을 한 번에 처리한다.
 * DB 라운드트립과 XACK 호출 수가 모두 N → 1 로 감소.</p>
 *
 * <h3>at-least-once 보장</h3>
 * <ul>
 *   <li>flush 성공(INSERT + XACK) → PEL 에서 제거</li>
 *   <li>INSERT 실패 → XACK 미실행 → idle 경과 후 {@link SearchLogStreamReclaimer} 가 회수</li>
 *   <li>프로세스 크래시(버퍼에만 있고 INSERT 전) → 메시지는 PEL 에 그대로 → Reclaimer 회수</li>
 *   <li>INSERT 성공 후 XACK 전 크래시 → 메시지 재전달되어 중복 INSERT 가능(통계 목적이라 허용)</li>
 * </ul>
 *
 * <h3>스레드 안전성</h3>
 * <p>{@code onMessage} 는 {@code StreamMessageListenerContainer} 의 내부 스레드에서 직렬 호출되고,
 * {@code scheduledFlush} 와 {@code destroy} 는 별도 스레드에서 호출된다. 모든 진입점이
 * {@code bufferLock} 으로 버퍼 drain 까지 보호하고, 무거운 DB 작업은 락 밖에서 실행한다.</p>
 */
public final class SearchLogStreamConsumer
        implements StreamListener<String, MapRecord<String, String, String>>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchLogStreamConsumer.class);

    private final SearchLogBatchWriter writer;
    private final SearchLogBrokerProperties properties;
    private final RedisStreamAcker acker;

    private final Deque<MapRecord<String, String, String>> buffer = new ArrayDeque<>();
    private final Object bufferLock = new Object();

    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong totalConsumeFailures = new AtomicLong(0);
    private final AtomicLong totalFlushBatches = new AtomicLong(0);

    public SearchLogStreamConsumer(SearchLogBatchWriter writer,
                                   SearchLogBrokerProperties properties,
                                   RedisStreamAcker acker) {
        this.writer = writer;
        this.properties = properties;
        this.acker = acker;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        boolean triggerFlush;
        synchronized (bufferLock) {
            buffer.offer(message);
            triggerFlush = buffer.size() >= properties.dbBatchSize();
        }
        if (triggerFlush) {
            flush();
        }
    }

    /**
     * [Phase 22-1] 주기 flush — dbBatchSize 도달 전이라도 일정 시간마다 버퍼를 비운다.
     * 짧은 부하 구간에서 메시지가 버퍼에 무한정 머무는 것을 방지한다.
     */
    @Scheduled(fixedDelayString = "${app.search-log.broker.batch-flush-interval:1s}")
    public void scheduledFlush() {
        flush();
    }

    /**
     * 버퍼의 모든 엔트리를 단일 배치로 처리. drain 은 락 안, INSERT/XACK 은 락 밖.
     */
    public void flush() {
        List<MapRecord<String, String, String>> batch;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        processBatch(batch);
    }

    private void processBatch(List<MapRecord<String, String, String>> batch) {
        List<SearchLogEntry> entries = batch.stream()
                .map(r -> toEntry(r.getValue()))
                .toList();
        try {
            writer.writeBatch(entries);
            RecordId[] ids = batch.stream().map(MapRecord::getId).toArray(RecordId[]::new);
            acker.ack(properties.stream(), properties.consumerGroup(), ids);
            totalConsumed.addAndGet(batch.size());
            totalFlushBatches.incrementAndGet();
        } catch (RuntimeException e) {
            // 미-ACK → Reclaimer 가 idle 경과 후 회수해 재처리한다.
            totalConsumeFailures.addAndGet(batch.size());
            log.warn("[Phase 22-1] 배치 컨슘 실패 — size={}, 첫 streamId={}",
                    batch.size(), batch.get(0).getId(), e);
        }
    }

    @Override
    public void destroy() {
        int remaining;
        synchronized (bufferLock) {
            remaining = buffer.size();
        }
        if (remaining > 0) {
            log.info("[Phase 22-1] Graceful Shutdown — 버퍼 잔량 flush: remaining={}", remaining);
            flush();
        }
    }

    static SearchLogEntry toEntry(Map<String, String> fields) {
        return new SearchLogEntry(
                parseNullableLong(fields.get(SearchLogStreamProducer.FIELD_USER_ID)),
                emptyToNull(fields.get(SearchLogStreamProducer.FIELD_KEYWORD)),
                parseInt(fields.get(SearchLogStreamProducer.FIELD_RESULT_COUNT)),
                emptyToNull(fields.get(SearchLogStreamProducer.FIELD_IP_ADDRESS)),
                emptyToNull(fields.get(SearchLogStreamProducer.FIELD_USER_AGENT)),
                parseSearchedAt(fields.get(SearchLogStreamProducer.FIELD_SEARCHED_AT)));
    }

    private static Long parseNullableLong(String v) {
        return (v == null || v.isEmpty()) ? null : Long.valueOf(v);
    }

    private static int parseInt(String v) {
        return (v == null || v.isEmpty()) ? 0 : Integer.parseInt(v);
    }

    private static String emptyToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static LocalDateTime parseSearchedAt(String v) {
        if (v == null || v.isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(v, SearchLogStreamProducer.SEARCHED_AT_FORMAT);
        } catch (DateTimeParseException e) {
            log.warn("[Phase 21] searchedAt 파싱 실패 — value={}, now() 로 대체", v);
            return LocalDateTime.now();
        }
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }

    public long getTotalConsumeFailures() {
        return totalConsumeFailures.get();
    }

    public long getTotalFlushBatches() {
        return totalFlushBatches.get();
    }

    int getBufferSize() {
        synchronized (bufferLock) {
            return buffer.size();
        }
    }

    /**
     * XACK 호출을 추상화한 작은 인터페이스. 테스트에서 Redis 없이 검증할 수 있도록 분리.
     * varargs 로 단건/배치 모두 지원한다.
     */
    public interface RedisStreamAcker {
        void ack(String stream, String group, RecordId... recordIds);
    }
}
