package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogBatchWriter;
import com.shop.domain.search.service.SearchLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SearchLogStreamConsumerTest {

    private SearchLogBatchWriter writer;
    private SearchLogStreamConsumer.RedisStreamAcker acker;
    private SearchLogStreamConsumer consumer;
    private SearchLogBrokerProperties properties;

    @BeforeEach
    void setUp() {
        writer = mock(SearchLogBatchWriter.class);
        acker = mock(SearchLogStreamConsumer.RedisStreamAcker.class);
        properties = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-1",
                100, Duration.ofSeconds(1), 100, 0,
                Duration.ofSeconds(60), Duration.ofSeconds(30), 5, "search-log-dlq");
        consumer = new SearchLogStreamConsumer(writer, properties, acker);
    }

    @Test
    @DisplayName("성공 컨슘 시 DB INSERT 후 XACK 가 호출된다")
    @SuppressWarnings("unchecked")
    void consumesAndAcks() {
        MapRecord<String, String, String> record = StreamRecords.mapBacked(Map.of(
                SearchLogStreamProducer.FIELD_USER_ID, "7",
                SearchLogStreamProducer.FIELD_KEYWORD, "jacket",
                SearchLogStreamProducer.FIELD_RESULT_COUNT, "10",
                SearchLogStreamProducer.FIELD_IP_ADDRESS, "127.0.0.1",
                SearchLogStreamProducer.FIELD_USER_AGENT, "agent",
                SearchLogStreamProducer.FIELD_SEARCHED_AT, "2026-05-15T12:00:00"
        )).withStreamKey("search-log-stream").withId(RecordId.of("100-0"));

        consumer.onMessage(record);

        ArgumentCaptor<List<SearchLogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeBatch(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(e -> {
            assertThat(e.userId()).isEqualTo(7L);
            assertThat(e.keyword()).isEqualTo("jacket");
            assertThat(e.resultCount()).isEqualTo(10);
            assertThat(e.ipAddress()).isEqualTo("127.0.0.1");
        });
        verify(acker).ack(eq("search-log-stream"), eq("search-log-cg"), eq(RecordId.of("100-0")));
        assertThat(consumer.getTotalConsumed()).isEqualTo(1);
        assertThat(consumer.getTotalConsumeFailures()).isZero();
    }

    @Test
    @DisplayName("DB INSERT 실패 시 XACK 가 호출되지 않아 다음 폴링에서 재전달된다")
    @SuppressWarnings("unchecked")
    void skipsAckOnFailure() {
        doThrow(new DataIntegrityViolationException("constraint violation"))
                .when(writer).writeBatch(any(List.class));

        MapRecord<String, String, String> record = StreamRecords.mapBacked(Map.of(
                SearchLogStreamProducer.FIELD_USER_ID, "",
                SearchLogStreamProducer.FIELD_KEYWORD, "shoes",
                SearchLogStreamProducer.FIELD_RESULT_COUNT, "0",
                SearchLogStreamProducer.FIELD_IP_ADDRESS, "",
                SearchLogStreamProducer.FIELD_USER_AGENT, "",
                SearchLogStreamProducer.FIELD_SEARCHED_AT, "2026-05-15T12:00:00"
        )).withStreamKey("search-log-stream").withId(RecordId.of("200-0"));

        consumer.onMessage(record);

        verify(acker, never()).ack(anyString(), anyString(), any(RecordId.class));
        assertThat(consumer.getTotalConsumed()).isZero();
        assertThat(consumer.getTotalConsumeFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("필드 매핑 — 빈 문자열은 null 로, searchedAt 형식 오류는 now() 로 복구")
    void mapsFieldsRobustly() {
        SearchLogEntry parsed = SearchLogStreamConsumer.toEntry(Map.of(
                SearchLogStreamProducer.FIELD_USER_ID, "",
                SearchLogStreamProducer.FIELD_KEYWORD, "shoes",
                SearchLogStreamProducer.FIELD_RESULT_COUNT, "5",
                SearchLogStreamProducer.FIELD_IP_ADDRESS, "",
                SearchLogStreamProducer.FIELD_USER_AGENT, "",
                SearchLogStreamProducer.FIELD_SEARCHED_AT, "not-a-date"));

        assertThat(parsed.userId()).isNull();
        assertThat(parsed.ipAddress()).isNull();
        assertThat(parsed.userAgent()).isNull();
        assertThat(parsed.resultCount()).isEqualTo(5);
        assertThat(parsed.searchedAt()).isNotNull(); // now() 로 복구
    }
}
