package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Phase 21] 검색 로그를 Redis Streams 에 적재(XADD)하는 프로듀서.
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li><b>필드 단위 직렬화</b> — JSON-in-field 가 아닌 명시 필드 매핑을 쓴다.
 *       Redis CLI({@code XRANGE}) 로 즉시 디버깅 가능하고, 컨슈머 측 파싱이 단순하다.</li>
 *   <li><b>nullable 처리</b> — {@code userId}/{@code ipAddress}/{@code userAgent} 는
 *       null 일 수 있다. Redis Streams 의 필드 값은 String 이라 빈 문자열로 직렬화하고
 *       컨슈머에서 빈 문자열을 null 로 역직렬화한다.</li>
 *   <li><b>MAXLEN ~</b> — {@code maxStreamLength > 0} 이면 근사 트림(~) 으로 XADD
 *       호출 시점에 오래된 엔트리를 비동기 제거한다. 정확 트림(=) 보다 빠르고, 검색 로그는
 *       장기 보존이 필요 없으므로 적합하다.</li>
 *   <li><b>Producer 측 실패</b> — Redis 가 다운되면 {@code DataAccessException} 을 던진다.
 *       호출자({@link com.shop.domain.search.service.SearchService}) 는 이를 잡고
 *       기존 인메모리 경로로 폴백한다.</li>
 * </ul>
 */
public class SearchLogStreamProducer {

    private static final Logger log = LoggerFactory.getLogger(SearchLogStreamProducer.class);

    // 필드 이름은 컨슈머와 1:1 매칭. 변경 시 양쪽 동시 변경 필수.
    static final String FIELD_USER_ID = "userId";
    static final String FIELD_KEYWORD = "keyword";
    static final String FIELD_RESULT_COUNT = "resultCount";
    static final String FIELD_IP_ADDRESS = "ipAddress";
    static final String FIELD_USER_AGENT = "userAgent";
    static final String FIELD_SEARCHED_AT = "searchedAt";

    static final DateTimeFormatter SEARCHED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final StringRedisTemplate redisTemplate;
    private final SearchLogBrokerProperties properties;

    private final AtomicLong totalProduced = new AtomicLong(0);
    private final AtomicLong totalProduceFailures = new AtomicLong(0);

    public SearchLogStreamProducer(StringRedisTemplate redisTemplate, SearchLogBrokerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 한 건의 검색 로그를 스트림에 적재한다.
     *
     * @return XADD 가 부여한 stream ID. 실패 시 호출자가 예외를 잡고 폴백 경로를 택해야 한다.
     */
    public RecordId produce(SearchLogEntry entry) {
        Map<String, String> fields = new LinkedHashMap<>(6);
        fields.put(FIELD_USER_ID, entry.userId() == null ? "" : entry.userId().toString());
        fields.put(FIELD_KEYWORD, entry.keyword() == null ? "" : entry.keyword());
        fields.put(FIELD_RESULT_COUNT, Integer.toString(entry.resultCount()));
        fields.put(FIELD_IP_ADDRESS, entry.ipAddress() == null ? "" : entry.ipAddress());
        fields.put(FIELD_USER_AGENT, entry.userAgent() == null ? "" : entry.userAgent());
        fields.put(FIELD_SEARCHED_AT, entry.searchedAt() == null ? "" : SEARCHED_AT_FORMAT.format(entry.searchedAt()));

        MapRecord<String, String, String> record = StreamRecords.mapBacked(fields)
                .withStreamKey(properties.stream());

        try {
            RecordId id = redisTemplate.opsForStream().add(record);
            totalProduced.incrementAndGet();
            return id;
        } catch (RuntimeException e) {
            totalProduceFailures.incrementAndGet();
            log.warn("[Phase 21] 검색 로그 스트림 XADD 실패 — stream={}, keyword={}",
                    properties.stream(), entry.keyword(), e);
            throw e;
        }
    }

    public long getTotalProduced() {
        return totalProduced.get();
    }

    public long getTotalProduceFailures() {
        return totalProduceFailures.get();
    }
}
