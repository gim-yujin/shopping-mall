package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogBatchWriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchLogBrokerMeterBinderTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, String, String> streamOps = mock(StreamOperations.class);
    private SearchLogBrokerProperties properties;
    private SearchLogStreamProducer producer;
    private SearchLogStreamConsumer consumer;
    private SearchLogStreamReclaimer reclaimer;
    private MeterRegistry registry;
    private SearchLogBrokerMeterBinder binder;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        doReturn(streamOps).when(redisTemplate).opsForStream();
        properties = new SearchLogBrokerProperties(
                true, "search-log-stream", "search-log-cg", "consumer-1",
                100, Duration.ofSeconds(1), 100, Duration.ofSeconds(1), 0,
                Duration.ofSeconds(60), Duration.ofSeconds(30), 5, "search-log-dlq");
        producer = new SearchLogStreamProducer(redisTemplate, properties);
        SearchLogStreamConsumer.RedisStreamAcker acker = mock(SearchLogStreamConsumer.RedisStreamAcker.class);
        consumer = new SearchLogStreamConsumer(mock(SearchLogBatchWriter.class), properties, acker);
        reclaimer = new SearchLogStreamReclaimer(redisTemplate, properties, consumer);
        registry = new SimpleMeterRegistry();
        binder = new SearchLogBrokerMeterBinder(producer, consumer, reclaimer, redisTemplate, properties);
        binder.bindTo(registry);
    }

    @Test
    @DisplayName("Producer/Consumer/Reclaimer 카운터 게이지가 내부 상태를 그대로 반영한다")
    @SuppressWarnings("unchecked")
    void counterGaugesReflectInternalState() {
        when(streamOps.add(any())).thenReturn(RecordId.of("1-0"));
        // Producer 한 번 호출
        producer.produce(new com.shop.domain.search.service.SearchLogEntry(
                1L, "k", 1, null, null, java.time.LocalDateTime.now()));

        assertGauge("shop.search.log.broker.produced.total", 1.0);
        assertGauge("shop.search.log.broker.consumed.total", 0.0);
        assertGauge("shop.search.log.broker.dlq.routed.total", 0.0);
    }

    @Test
    @DisplayName("stream.length / dlq.length 게이지는 XLEN 결과를 그대로 노출한다")
    void streamLengthGaugesReadXlen() {
        when(streamOps.size(eq("search-log-stream"))).thenReturn(42L);
        when(streamOps.size(eq("search-log-dlq"))).thenReturn(3L);

        assertGauge("shop.search.log.broker.stream.length", 42.0);
        assertGauge("shop.search.log.broker.dlq.length", 3.0);
    }

    @Test
    @DisplayName("Redis 호출 예외 시 게이지는 0 으로 폴백한다(메트릭 스크레이프가 깨지지 않음)")
    void redisFailureFallsBackToZero() {
        when(streamOps.size(eq("search-log-stream")))
                .thenThrow(new QueryTimeoutException("Redis down"));

        assertGauge("shop.search.log.broker.stream.length", 0.0);
    }

    @Test
    @DisplayName("pel.size 게이지는 XPENDING summary 의 totalPendingMessages 를 노출한다")
    void pelSizeGaugeReadsXpending() {
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(7L);
        when(streamOps.pending(eq("search-log-stream"), eq("search-log-cg"))).thenReturn(summary);

        assertGauge("shop.search.log.broker.pel.size", 7.0);
    }

    private void assertGauge(String name, double expected) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as("게이지 %s 가 등록되어 있어야 함", name).isNotNull();
        assertThat(gauge.value()).as("게이지 %s 값", name).isEqualTo(expected);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
