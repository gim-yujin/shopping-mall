package com.shop.global.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Outbox 이벤트 폴러.
 *
 * <h3>동작 원리</h3>
 * <p>5초 간격으로 {@code outbox_events} 테이블에서 PENDING 상태의 이벤트를 조회하고,
 * 이벤트 유형에 따라 적절한 핸들러(캐시 무효화 등)를 실행한다.
 * 처리 성공 시 PROCESSED로, 최대 재시도 초과 시 FAILED로 상태를 전이한다.</p>
 *
 * <h3>왜 CDC(Change Data Capture)가 아닌 폴링인가?</h3>
 * <ul>
 *   <li>Debezium 등 CDC 도구는 Kafka + ZooKeeper + Connector 인프라가 필요하다.
 *       단일 인스턴스 + PostgreSQL 구성에서는 과도한 복잡성이다.</li>
 *   <li>캐시 무효화의 지연 허용 범위(수초)에서 5초 폴링은 충분히 빠르다.
 *       실시간성이 필요한 경우(채팅, 알림) 향후 CDC로 전환할 수 있다.</li>
 *   <li>폴링은 구현이 단순하고 디버깅이 쉬우며, 모니터링(Grafana)도 용이하다.</li>
 * </ul>
 *
 * <h3>재시도 전략</h3>
 * <p>처리 실패 시 retry_count를 증가시키고 PENDING 상태를 유지하여
 * 다음 폴링 주기에 자동 재시도한다. MAX_RETRIES(기본 5회) 초과 시
 * FAILED로 전이하여 무한 재시도를 방지한다.
 * FAILED 이벤트는 관리자가 수동으로 확인하거나 알림을 통해 인지한다.</p>
 *
 * <h3>at-least-once 보장</h3>
 * <p>이벤트 처리 후 PROCESSED로 전이하기 전에 크래시하면 다음 폴링에서
 * 동일 이벤트가 재처리된다. 따라서 핸들러는 멱등(idempotent)해야 한다.
 * 캐시 무효화({@link ProductCacheEvictHelper})는 본질적으로 멱등하다 —
 * 이미 제거된 캐시를 다시 제거해도 부작용이 없다.</p>
 *
 * <h3>폴링 간격과 배치 크기</h3>
 * <p>fixedDelay=5000ms: 이전 실행 완료 후 5초 대기. fixedRate와 달리
 * 처리가 오래 걸려도 중복 실행이 발생하지 않는다.
 * batchSize=100: 한 번에 100건까지 처리. 급격한 트래픽 증가 시에도
 * 폴러가 과도한 메모리를 소비하지 않도록 제한한다.</p>
 */
@Component
public class OutboxEventPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ProductCacheEvictHelper productCacheEvictHelper;
    private final ObjectMapper objectMapper;
    private final int maxRetries;
    private final int batchSize;

    public OutboxEventPoller(OutboxEventRepository outboxEventRepository,
                              ProductCacheEvictHelper productCacheEvictHelper,
                              ObjectMapper objectMapper,
                              @Value("${app.outbox.max-retries:5}") int maxRetries,
                              @Value("${app.outbox.batch-size:100}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.productCacheEvictHelper = productCacheEvictHelper;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
    }

    /**
     * PENDING 이벤트를 폴링하여 처리한다.
     *
     * <p>각 이벤트를 개별 처리하고, 하나의 실패가 다른 이벤트 처리를
     * 중단하지 않도록 이벤트 단위로 예외를 catch한다.
     * 전체 메서드가 하나의 트랜잭션으로 실행되어, 모든 상태 전이가
     * 원자적으로 커밋된다.</p>
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollAndProcess() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEvents(batchSize);
        if (events.isEmpty()) {
            return;
        }

        int processed = 0;
        int failed = 0;

        for (OutboxEvent event : events) {
            try {
                processEvent(event);
                event.markProcessed();
                processed++;
            } catch (Exception e) {
                event.incrementRetry();
                if (event.getRetryCount() >= maxRetries) {
                    event.markFailed();
                    failed++;
                    log.error("Outbox 이벤트 영구 실패 ({}회 재시도 초과) - eventId={}, type={}, payload={}",
                            maxRetries, event.getEventId(), event.getEventType(),
                            event.getPayload(), e);
                } else {
                    log.warn("Outbox 이벤트 처리 실패 (재시도 {}/{}) - eventId={}, type={}",
                            event.getRetryCount(), maxRetries,
                            event.getEventId(), event.getEventType(), e);
                }
            }
        }

        if (processed > 0 || failed > 0) {
            log.info("Outbox 폴링 완료 - processed={}, failed={}, total={}", processed, failed, events.size());
        }
    }

    /**
     * 이벤트 유형에 따라 적절한 핸들러를 실행한다.
     *
     * <p>현재는 PRODUCT_STOCK_CHANGED만 처리하지만, 이벤트 유형이 추가되면
     * switch 분기를 확장한다. 향후 이벤트 유형이 많아지면
     * Strategy 패턴으로 핸들러를 분리할 수 있다.</p>
     */
    private void processEvent(OutboxEvent event) {
        switch (event.getEventType()) {
            case OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED -> handleStockChanged(event);
            default -> log.warn("알 수 없는 Outbox 이벤트 유형: {}", event.getEventType());
        }
    }

    /**
     * 상품 재고 변경 이벤트를 처리한다 — 상품 상세 캐시 무효화.
     *
     * <p>기존 {@link com.shop.domain.product.service.ProductStockChangedEventListener}가
     * 수행하던 동일한 캐시 무효화 로직을 실행한다.
     * {@link ProductCacheEvictHelper}는 멱등하므로 at-least-once 재처리에 안전하다.</p>
     *
     * @param event payload 형식: {"productIds":[1,2,3]}
     */
    private void handleStockChanged(OutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Number> rawIds = (List<Number>) payload.get("productIds");
            if (rawIds == null || rawIds.isEmpty()) {
                log.warn("PRODUCT_STOCK_CHANGED 이벤트에 productIds가 없음 - eventId={}",
                        event.getEventId());
                return;
            }
            List<Long> productIds = rawIds.stream().map(Number::longValue).toList();
            productCacheEvictHelper.evictProductDetailCaches(productIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Outbox 이벤트 페이로드 파싱 실패 - eventId=" + event.getEventId(), e);
        }
    }
}
