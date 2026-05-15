package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogBatchWriter;
import com.shop.domain.search.service.SearchLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.stream.StreamListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Phase 21] Redis Streams 컨슈머. {@link SearchLogStreamProducer} 가 적재한 메시지를
 * 읽어 {@link SearchLogBatchWriter} 로 DB INSERT 한다.
 *
 * <h3>실행 방식</h3>
 * <p>{@code SearchLogBrokerConfig} 가 등록한 {@code StreamMessageListenerContainer} 가
 * {@code XREADGROUP} 을 지속 폴링하며 본 리스너로 메시지를 전달한다. 컨테이너는 내부
 * 스레드에서 실행되므로 본 클래스는 별도 스케줄러 없이 메시지가 도착할 때마다 호출된다.</p>
 *
 * <h3>at-least-once 보장</h3>
 * <ul>
 *   <li>DB INSERT 성공 → XACK → PEL 에서 제거</li>
 *   <li>DB INSERT 실패 → XACK 하지 않음 → idle 경과 후 {@link SearchLogStreamReclaimer} 가 회수</li>
 *   <li>Consumer 크래시 (XACK 전) → 같음, Reclaimer 가 회수</li>
 * </ul>
 *
 * <h3>배치 INSERT 트레이드오프</h3>
 * <p>Spring Data Redis 의 {@code StreamListener} 콜백은 단건 기준이라, Phase 19 의
 * JDBC 배치 INSERT 이점은 본 경로에서 일부 상실된다. 대신 Redis Streams 자체가
 * 프로듀서/컨슈머 간 버퍼 역할을 하고, 컨슈머를 수평 확장(같은 그룹·다른 컨슈머
 * 이름)할 수 있어 throughput 은 인스턴스 수로 보전한다. 본 도메인의 우선순위는
 * "내구성·재처리" 이므로 단순함을 택한다. 추후 필요 시 컨테이너의 batchSize 콜백
 * 옵션을 활용한 배치 리스너로 확장 가능하다.</p>
 */
public final class SearchLogStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(SearchLogStreamConsumer.class);

    private final SearchLogBatchWriter writer;
    private final SearchLogBrokerProperties properties;
    private final RedisStreamAcker acker;

    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong totalConsumeFailures = new AtomicLong(0);

    public SearchLogStreamConsumer(SearchLogBatchWriter writer,
                                   SearchLogBrokerProperties properties,
                                   RedisStreamAcker acker) {
        this.writer = writer;
        this.properties = properties;
        this.acker = acker;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            SearchLogEntry entry = toEntry(message.getValue());
            writer.writeBatch(List.of(entry));
            acker.ack(properties.stream(), properties.consumerGroup(), message.getId());
            totalConsumed.incrementAndGet();
        } catch (RuntimeException e) {
            // XACK 하지 않음 → 다음 폴링에서 재전달, Reclaimer 가 idle 회수 처리.
            totalConsumeFailures.incrementAndGet();
            log.warn("[Phase 21] 검색 로그 컨슘 실패 — streamId={}, fields={}",
                    message.getId(), message.getValue(), e);
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

    /**
     * XACK 호출을 추상화한 작은 인터페이스. 테스트에서 Redis 없이 검증할 수 있도록 분리한다.
     */
    public interface RedisStreamAcker {
        void ack(String stream, String group, RecordId recordId);
    }
}
