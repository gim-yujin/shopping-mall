package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchLogStreamProducerTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOps = mock(StreamOperations.class);
    private SearchLogBrokerProperties properties;
    private SearchLogStreamProducer producer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        // doReturn 우회: opsForStream() 의 제네릭 시그니처(HK/HV) 가 호출 위치별로
        // Object/Object 로 추론돼 when().thenReturn() 이 타입 불일치를 일으킨다.
        doReturn(streamOps).when(redisTemplate).opsForStream();
        properties = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-1",
                100, Duration.ofSeconds(1), 100, Duration.ofSeconds(1), 0,
                Duration.ofSeconds(60), Duration.ofSeconds(30), 5, "search-log-dlq");
        producer = new SearchLogStreamProducer(redisTemplate, properties);
    }

    @Test
    @DisplayName("XADD 시 명시 필드로 직렬화하며 nullable 필드는 빈 문자열로 변환된다")
    @SuppressWarnings("unchecked")
    void producesWithExplicitFields() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        SearchLogEntry entry = new SearchLogEntry(
                42L, "shoes", 15, null, "Mozilla/5.0",
                LocalDateTime.of(2026, 5, 15, 12, 0, 0));

        RecordId id = producer.produce(entry);

        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        MapRecord<String, String, String> sent = captor.getValue();

        assertThat(sent.getStream()).isEqualTo("search-log-stream");
        assertThat(sent.getValue())
                .containsEntry(SearchLogStreamProducer.FIELD_USER_ID, "42")
                .containsEntry(SearchLogStreamProducer.FIELD_KEYWORD, "shoes")
                .containsEntry(SearchLogStreamProducer.FIELD_RESULT_COUNT, "15")
                .containsEntry(SearchLogStreamProducer.FIELD_IP_ADDRESS, "")
                .containsEntry(SearchLogStreamProducer.FIELD_USER_AGENT, "Mozilla/5.0")
                .containsEntry(SearchLogStreamProducer.FIELD_SEARCHED_AT, "2026-05-15T12:00:00");
        assertThat(id).isEqualTo(RecordId.of("1-0"));
        assertThat(producer.getTotalProduced()).isEqualTo(1);
        assertThat(producer.getTotalProduceFailures()).isZero();
    }

    @Test
    @DisplayName("Redis 예외 발생 시 실패 카운터 증가 후 예외를 다시 던진다(호출자가 폴백)")
    @SuppressWarnings("unchecked")
    void rethrowsAndCountsFailures() {
        when(streamOps.add(any(MapRecord.class)))
                .thenThrow(new QueryTimeoutException("Redis down"));

        SearchLogEntry entry = new SearchLogEntry(
                null, "boots", 0, null, null, LocalDateTime.now());

        assertThatThrownBy(() -> producer.produce(entry))
                .isInstanceOf(QueryTimeoutException.class);

        assertThat(producer.getTotalProduced()).isZero();
        assertThat(producer.getTotalProduceFailures()).isEqualTo(1);
        verify(streamOps, times(1)).add(any(MapRecord.class));
    }

    @Test
    @DisplayName("null userId 는 빈 문자열로 직렬화된다")
    @SuppressWarnings("unchecked")
    void serializesNullUserIdAsEmptyString() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("2-0"));

        SearchLogEntry entry = new SearchLogEntry(
                null, "guest-search", 3, "10.0.0.1", "agent", LocalDateTime.now());

        producer.produce(entry);

        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertThat(captor.getValue().getValue())
                .containsEntry(SearchLogStreamProducer.FIELD_USER_ID, "");
    }

    @Test
    @DisplayName("[Phase 22-2] maxStreamLength > 0 이면 XADD 가 MAXLEN ~ N 옵션과 함께 호출된다")
    @SuppressWarnings("unchecked")
    void appliesApproximateMaxLenWhenConfigured() {
        SearchLogBrokerProperties withMaxLen = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-1",
                100, java.time.Duration.ofSeconds(1), 100, java.time.Duration.ofSeconds(1),
                100_000, java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(30),
                5, "search-log-dlq");
        SearchLogStreamProducer producerWithMaxLen = new SearchLogStreamProducer(redisTemplate, withMaxLen);

        when(streamOps.add(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class)))
                .thenReturn(RecordId.of("3-0"));

        SearchLogEntry entry = new SearchLogEntry(
                1L, "k", 1, null, null, LocalDateTime.now());
        producerWithMaxLen.produce(entry);

        ArgumentCaptor<RedisStreamCommands.XAddOptions> optCaptor =
                ArgumentCaptor.forClass(RedisStreamCommands.XAddOptions.class);
        verify(streamOps).add(any(MapRecord.class), optCaptor.capture());
        assertThat(optCaptor.getValue().getMaxlen()).isEqualTo(100_000L);
        assertThat(optCaptor.getValue().isApproximateTrimming()).isTrue();
    }

    @Test
    @DisplayName("[Phase 22-2] maxStreamLength == 0 이면 기존 단순 XADD 호출 (옵션 미적용)")
    @SuppressWarnings("unchecked")
    void skipsMaxLenWhenZero() {
        // 기본 properties (maxStreamLength=0) — setUp 의 producer 그대로 사용.
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("4-0"));

        producer.produce(new SearchLogEntry(
                1L, "k", 1, null, null, LocalDateTime.now()));

        verify(streamOps).add(any(MapRecord.class));
        verify(streamOps, org.mockito.Mockito.never())
                .add(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class));
    }
}
