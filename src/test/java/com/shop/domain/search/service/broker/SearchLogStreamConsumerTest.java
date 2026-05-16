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
import static org.mockito.Mockito.verifyNoInteractions;

class SearchLogStreamConsumerTest {

    private SearchLogBatchWriter writer;
    private SearchLogStreamConsumer.RedisStreamAcker acker;
    private SearchLogStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        writer = mock(SearchLogBatchWriter.class);
        acker = mock(SearchLogStreamConsumer.RedisStreamAcker.class);
        SearchLogBrokerProperties properties = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-1",
                100, Duration.ofSeconds(1), 3, Duration.ofSeconds(1), 0,
                Duration.ofSeconds(60), Duration.ofSeconds(30), 5, "search-log-dlq");
        consumer = new SearchLogStreamConsumer(writer, properties, acker);
    }

    @Test
    @DisplayName("dbBatchSize 미달 시 onMessage 는 버퍼에 누적만 — INSERT/ACK 즉시 호출 안 함")
    void bufferAccumulatesUntilThreshold() {
        consumer.onMessage(record("100-0", "k1"));
        consumer.onMessage(record("100-1", "k2"));

        assertThat(consumer.getBufferSize()).isEqualTo(2);
        verifyNoInteractions(writer);
        verifyNoInteractions(acker);
    }

    @Test
    @DisplayName("dbBatchSize 도달 시 자동 flush — 배치 INSERT + 배치 XACK 단일 호출")
    @SuppressWarnings("unchecked")
    void autoFlushOnBatchSize() {
        // dbBatchSize=3 → 3건째 onMessage 시점에 flush 트리거
        consumer.onMessage(record("100-0", "k1"));
        consumer.onMessage(record("100-1", "k2"));
        consumer.onMessage(record("100-2", "k3"));

        ArgumentCaptor<List<SearchLogEntry>> writeCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).writeBatch(writeCaptor.capture());
        assertThat(writeCaptor.getValue()).hasSize(3)
                .extracting(SearchLogEntry::keyword)
                .containsExactly("k1", "k2", "k3");

        ArgumentCaptor<RecordId[]> ackCaptor = ArgumentCaptor.forClass(RecordId[].class);
        verify(acker).ack(eq("search-log-stream"), eq("search-log-cg"), ackCaptor.capture());
        assertThat(ackCaptor.getValue())
                .containsExactly(RecordId.of("100-0"), RecordId.of("100-1"), RecordId.of("100-2"));

        assertThat(consumer.getTotalConsumed()).isEqualTo(3);
        assertThat(consumer.getTotalFlushBatches()).isEqualTo(1);
        assertThat(consumer.getBufferSize()).isZero();
    }

    @Test
    @DisplayName("scheduledFlush 는 미달 버퍼도 비운다")
    void scheduledFlushClearsPartialBuffer() {
        consumer.onMessage(record("100-0", "k1"));
        consumer.onMessage(record("100-1", "k2"));

        consumer.scheduledFlush();

        verify(writer).writeBatch(any());
        verify(acker).ack(anyString(), anyString(), any(RecordId[].class));
        assertThat(consumer.getBufferSize()).isZero();
    }

    @Test
    @DisplayName("빈 버퍼 flush 는 INSERT/ACK 를 호출하지 않는다")
    void emptyFlushIsNoOp() {
        consumer.flush();

        verifyNoInteractions(writer);
        verifyNoInteractions(acker);
        assertThat(consumer.getTotalFlushBatches()).isZero();
    }

    @Test
    @DisplayName("DB INSERT 실패 시 XACK 미실행, 실패 카운터는 배치 전체로 증가")
    @SuppressWarnings("unchecked")
    void noAckOnBatchInsertFailure() {
        doThrow(new DataIntegrityViolationException("constraint violation"))
                .when(writer).writeBatch(any(List.class));

        consumer.onMessage(record("100-0", "k1"));
        consumer.onMessage(record("100-1", "k2"));
        consumer.flush();

        verify(writer).writeBatch(any());
        verify(acker, never()).ack(anyString(), anyString(), any(RecordId[].class));
        assertThat(consumer.getTotalConsumed()).isZero();
        assertThat(consumer.getTotalConsumeFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("destroy 는 잔여 버퍼를 flush 해 graceful shutdown 시 유실을 최소화한다")
    void destroyFlushesRemaining() {
        consumer.onMessage(record("100-0", "k1"));

        consumer.destroy();

        verify(writer).writeBatch(any());
        assertThat(consumer.getBufferSize()).isZero();
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
        assertThat(parsed.searchedAt()).isNotNull();
    }

    private static MapRecord<String, String, String> record(String id, String keyword) {
        return StreamRecords.mapBacked(Map.of(
                SearchLogStreamProducer.FIELD_USER_ID, "",
                SearchLogStreamProducer.FIELD_KEYWORD, keyword,
                SearchLogStreamProducer.FIELD_RESULT_COUNT, "0",
                SearchLogStreamProducer.FIELD_IP_ADDRESS, "",
                SearchLogStreamProducer.FIELD_USER_AGENT, "",
                SearchLogStreamProducer.FIELD_SEARCHED_AT, "2026-05-16T12:00:00"
        )).withStreamKey("search-log-stream").withId(RecordId.of(id));
    }
}
