package com.shop.domain.search.service.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Phase 21] PEL(Pending Entries List) 회수 + DLQ 라우팅.
 *
 * <h3>책임</h3>
 * <ol>
 *   <li><b>고립 메시지 회수</b> — 다른 컨슈머가 처리 중이던 메시지가 idle 한계를 넘으면
 *       {@code XPENDING + XCLAIM} 으로 본 인스턴스로 이전받아 재처리한다.</li>
 *   <li><b>독성 메시지 격리</b> — {@code maxDeliveryAttempts} 를 넘은 메시지는
 *       원본을 읽어 DLQ 스트림({@code dlqStream}) 으로 복사한 뒤 원본 XACK 하여 PEL 에서 제거.</li>
 * </ol>
 *
 * <h3>왜 XAUTOCLAIM 이 아니라 XPENDING + XCLAIM 인가</h3>
 * <p>Spring Data Redis 3.4 의 {@code StreamOperations} 는 XAUTOCLAIM 을 직접 노출하지 않는다.
 * 대신 {@code pending(stream, consumer)} 로 PEL 을 조회하고 개별 ID 에 {@code claim} 을
 * 호출한다. 양쪽 모두 의미론은 동일하다 — idle 임계를 넘은 메시지를 현재 컨슈머로 이전.</p>
 *
 * <h3>중복 위험</h3>
 * <p>Reclaim 직후 DB 가 이미 그 entry 를 갖고 있을 수 있다(원래 컨슈머가 INSERT 직후
 * XACK 전에 죽은 경우). 검색 로그는 통계 목적이고 중복 정책({@link com.shop.domain.search.service.SearchLogBatchAccumulator}
 * 의 동일 정책) 을 따르므로 허용한다.</p>
 */
public class SearchLogStreamReclaimer {

    private static final Logger log = LoggerFactory.getLogger(SearchLogStreamReclaimer.class);

    private final StringRedisTemplate redisTemplate;
    private final SearchLogBrokerProperties properties;
    private final SearchLogStreamConsumer consumer;

    private final AtomicLong totalReclaimed = new AtomicLong(0);
    private final AtomicLong totalRoutedToDlq = new AtomicLong(0);

    public SearchLogStreamReclaimer(StringRedisTemplate redisTemplate,
                                    SearchLogBrokerProperties properties,
                                    SearchLogStreamConsumer consumer) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.consumer = consumer;
    }

    /**
     * 주기적 PEL 스캔. {@code app.search-log.broker.reclaim-interval} 로 주기 조정 (ISO-8601 또는 "30s" 형식).
     */
    @Scheduled(fixedDelayString = "${app.search-log.broker.reclaim-interval:30s}")
    public void scanPendingEntries() {
        try {
            reclaimOnce();
        } catch (RuntimeException e) {
            log.warn("[Phase 21] PEL 스캔 중 예외 — stream={}, group={}",
                    properties.stream(), properties.consumerGroup(), e);
        }
    }

    void reclaimOnce() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        PendingMessagesSummary summary;
        try {
            summary = ops.pending(properties.stream(), properties.consumerGroup());
        } catch (RuntimeException e) {
            // 그룹이 아직 없으면 첫 폴링 직전 시점 — 정상 케이스.
            return;
        }
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return;
        }

        PendingMessages pending = ops.pending(
                properties.stream(),
                Consumer.from(properties.consumerGroup(), properties.consumerName()),
                org.springframework.data.domain.Range.unbounded(),
                properties.pollBatchSize());

        if (pending == null || pending.isEmpty()) {
            // 본 컨슈머에 할당된 PEL 이 없으면 다른 컨슈머의 idle 메시지 회수만 시도.
            scanAcrossConsumers(ops);
            return;
        }

        for (PendingMessage pm : pending) {
            handlePending(ops, pm);
        }
    }

    private void scanAcrossConsumers(StreamOperations<String, String, String> ops) {
        PendingMessages all = ops.pending(
                properties.stream(),
                properties.consumerGroup(),
                org.springframework.data.domain.Range.unbounded(),
                properties.pollBatchSize());
        if (all == null || all.isEmpty()) {
            return;
        }
        for (PendingMessage pm : all) {
            // 자기 자신 메시지는 위 분기에서 이미 처리됨 — 다른 컨슈머의 idle 메시지만 회수
            if (properties.consumerName().equals(pm.getConsumerName())) {
                continue;
            }
            handlePending(ops, pm);
        }
    }

    private void handlePending(StreamOperations<String, String, String> ops, PendingMessage pm) {
        Duration idle = pm.getElapsedTimeSinceLastDelivery();
        if (idle == null || idle.compareTo(properties.claimIdle()) < 0) {
            return;
        }
        if (pm.getTotalDeliveryCount() > properties.maxDeliveryAttempts()) {
            routeToDlq(ops, pm);
            return;
        }
        claimAndReprocess(ops, pm);
    }

    private void claimAndReprocess(StreamOperations<String, String, String> ops, PendingMessage pm) {
        List<MapRecord<String, String, String>> claimed = ops.claim(
                properties.stream(),
                properties.consumerGroup(),
                properties.consumerName(),
                properties.claimIdle(),
                pm.getId());

        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        totalReclaimed.addAndGet(claimed.size());
        for (MapRecord<String, String, String> record : claimed) {
            consumer.onMessage(record);
        }
    }

    private void routeToDlq(StreamOperations<String, String, String> ops, PendingMessage pm) {
        // 원본을 읽기 위해 XRANGE 로 단건 조회.
        List<MapRecord<String, String, String>> records = ops.range(
                properties.stream(),
                org.springframework.data.domain.Range.closed(pm.getIdAsString(), pm.getIdAsString()));

        if (records == null || records.isEmpty()) {
            // 이미 누군가 삭제했을 수 있음 — 그래도 XACK 로 PEL 정리.
            ackSafely(ops, pm.getId());
            return;
        }
        Map<String, String> payload = records.get(0).getValue();
        try {
            ops.add(StreamRecords.mapBacked(payload).withStreamKey(properties.dlqStream()));
            totalRoutedToDlq.incrementAndGet();
            log.warn("[Phase 21] DLQ 라우팅 — id={}, deliveries={}, dlq={}",
                    pm.getIdAsString(), pm.getTotalDeliveryCount(), properties.dlqStream());
        } finally {
            ackSafely(ops, pm.getId());
        }
    }

    private void ackSafely(StreamOperations<String, String, String> ops, RecordId id) {
        try {
            ops.acknowledge(properties.stream(), properties.consumerGroup(), id);
        } catch (RuntimeException e) {
            log.warn("[Phase 21] XACK 실패 — id={}", id, e);
        }
    }

    /**
     * 테스트/시작 시 그룹이 이미 존재하는지 안전하게 생성한다. BUSYGROUP 은 무시.
     */
    static void ensureGroupExists(StringRedisTemplate template, String stream, String group) {
        StreamOperations<String, String, String> ops = template.opsForStream();
        try {
            ops.createGroup(stream, org.springframework.data.redis.connection.stream.ReadOffset.from("0"), group);
        } catch (RuntimeException e) {
            String msg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return;
            }
            // 스트림이 아직 없으면 빈 페이로드로 한 번 XADD 해서 스트림을 만들고 다시 그룹 생성.
            try {
                ops.add(StreamRecords.mapBacked(Map.of("init", "true")).withStreamKey(stream));
                ops.createGroup(stream, org.springframework.data.redis.connection.stream.ReadOffset.from("0"), group);
            } catch (RuntimeException ignored) {
                // 이미 다른 인스턴스가 만든 경우 등 — 다음 폴링에서 정상화됨.
            }
        }
    }

    public long getTotalReclaimed() {
        return totalReclaimed.get();
    }

    public long getTotalRoutedToDlq() {
        return totalRoutedToDlq.get();
    }
}
