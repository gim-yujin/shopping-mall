package com.shop.domain.search.service.broker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * [Phase 21] Redis Streams 브로커 메트릭을 Micrometer 에 노출.
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <caption>broker counters &amp; gauges</caption>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.search.log.broker.produced.total</td><td>Gauge</td><td>Producer XADD 누적 성공 건수</td></tr>
 *   <tr><td>shop.search.log.broker.produce.failures.total</td><td>Gauge</td><td>Producer XADD 누적 실패 건수</td></tr>
 *   <tr><td>shop.search.log.broker.consumed.total</td><td>Gauge</td><td>Consumer DB INSERT + XACK 누적 성공 건수</td></tr>
 *   <tr><td>shop.search.log.broker.consume.failures.total</td><td>Gauge</td><td>Consumer 누적 실패 건수(미-ACK)</td></tr>
 *   <tr><td>shop.search.log.broker.flush.batches</td><td>Gauge</td><td>[Phase 22-1] flush 누적 횟수 — 평균 배치 크기는 consumed/flush.batches 로 산출</td></tr>
 *   <tr><td>shop.search.log.broker.reclaimed.total</td><td>Gauge</td><td>Reclaimer 가 XCLAIM 으로 회수한 누적 건수</td></tr>
 *   <tr><td>shop.search.log.broker.dlq.routed.total</td><td>Gauge</td><td>maxDeliveryAttempts 초과로 DLQ 로 라우팅된 누적 건수</td></tr>
 *   <tr><td>shop.search.log.broker.stream.length</td><td>Gauge</td><td>현재 메인 스트림의 엔트리 수(XLEN)</td></tr>
 *   <tr><td>shop.search.log.broker.dlq.length</td><td>Gauge</td><td>현재 DLQ 스트림의 엔트리 수(XLEN)</td></tr>
 *   <tr><td>shop.search.log.broker.pel.size</td><td>Gauge</td><td>현재 PEL(미-ACK) 크기(XPENDING)</td></tr>
 * </table>
 *
 * <p>알림 기준 예시:</p>
 * <ul>
 *   <li>{@code increase(shop.search.log.broker.dlq.routed.total[5m]) > 0} — 독성 메시지 발생</li>
 *   <li>{@code shop.search.log.broker.pel.size > 1000} — 컨슘 적체(컨슈머 다운 or 느림)</li>
 *   <li>{@code rate(shop.search.log.broker.consume.failures.total[1m]) &gt; 0} — DB 인서트 실패 지속</li>
 * </ul>
 *
 * <p>Redis 호출(stream length, PEL size) 은 Gauge lambda 가 스크레이프마다 호출하므로 비용을 무시한다.
 * Redis 일시 장애 시에는 이전 측정값(혹은 0) 을 반환해 메트릭 수집 자체가 깨지지 않도록 한다.</p>
 */
public class SearchLogBrokerMeterBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SearchLogBrokerMeterBinder.class);

    private final SearchLogStreamProducer producer;
    private final SearchLogStreamConsumer consumer;
    private final SearchLogStreamReclaimer reclaimer;
    private final StringRedisTemplate redisTemplate;
    private final SearchLogBrokerProperties properties;

    public SearchLogBrokerMeterBinder(
            SearchLogStreamProducer producer,
            SearchLogStreamConsumer consumer,
            SearchLogStreamReclaimer reclaimer,
            StringRedisTemplate redisTemplate,
            SearchLogBrokerProperties properties) {
        this.producer = producer;
        this.consumer = consumer;
        this.reclaimer = reclaimer;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // ── Producer 카운터 ──
        Gauge.builder("shop.search.log.broker.produced.total", producer,
                        p -> (double) p.getTotalProduced())
                .description("Producer XADD 누적 성공 건수")
                .register(registry);
        Gauge.builder("shop.search.log.broker.produce.failures.total", producer,
                        p -> (double) p.getTotalProduceFailures())
                .description("Producer XADD 누적 실패 건수(호출자 폴백 트리거)")
                .register(registry);

        // ── Consumer 카운터 ──
        Gauge.builder("shop.search.log.broker.consumed.total", consumer,
                        c -> (double) c.getTotalConsumed())
                .description("Consumer DB INSERT + XACK 누적 성공 건수")
                .register(registry);
        Gauge.builder("shop.search.log.broker.consume.failures.total", consumer,
                        c -> (double) c.getTotalConsumeFailures())
                .description("Consumer 누적 실패 건수(미-ACK)")
                .register(registry);
        Gauge.builder("shop.search.log.broker.flush.batches", consumer,
                        c -> (double) c.getTotalFlushBatches())
                .description("[Phase 22-1] flush 누적 횟수 — 평균 배치 크기 = consumed / flush.batches")
                .register(registry);

        // ── Reclaimer 카운터 ──
        Gauge.builder("shop.search.log.broker.reclaimed.total", reclaimer,
                        r -> (double) r.getTotalReclaimed())
                .description("Reclaimer 가 XCLAIM 으로 회수한 누적 건수")
                .register(registry);
        Gauge.builder("shop.search.log.broker.dlq.routed.total", reclaimer,
                        r -> (double) r.getTotalRoutedToDlq())
                .description("maxDeliveryAttempts 초과로 DLQ 로 라우팅된 누적 건수")
                .register(registry);

        // ── Redis 쿼리 기반 게이지 ──
        Gauge.builder("shop.search.log.broker.stream.length", this,
                        b -> b.streamLengthSafely(properties.stream()))
                .description("메인 스트림의 현재 엔트리 수(XLEN)")
                .register(registry);
        Gauge.builder("shop.search.log.broker.dlq.length", this,
                        b -> b.streamLengthSafely(properties.dlqStream()))
                .description("DLQ 스트림의 현재 엔트리 수(XLEN)")
                .register(registry);
        Gauge.builder("shop.search.log.broker.pel.size", this,
                        SearchLogBrokerMeterBinder::pelSizeSafely)
                .description("현재 PEL(미-ACK) 크기(XPENDING)")
                .register(registry);
    }

    private double streamLengthSafely(String streamKey) {
        try {
            StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
            Long size = ops.size(streamKey);
            return size == null ? 0.0 : size.doubleValue();
        } catch (RuntimeException e) {
            log.debug("[Phase 21] XLEN 측정 실패 — stream={}", streamKey, e);
            return 0.0;
        }
    }

    private double pelSizeSafely() {
        try {
            StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
            PendingMessagesSummary summary = ops.pending(properties.stream(), properties.consumerGroup());
            return summary == null ? 0.0 : (double) summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            log.debug("[Phase 21] XPENDING 측정 실패 — stream={}, group={}",
                    properties.stream(), properties.consumerGroup(), e);
            return 0.0;
        }
    }
}
