package com.shop.global.outbox;

import com.shop.global.metrics.OutboxMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <h3>[Phase 6] Strategy 패턴 적용</h3>
 * <p>기존 switch 문 기반 이벤트 라우팅을 {@link OutboxEventHandler} 전략 패턴으로 교체했다.
 * Spring이 모든 {@code OutboxEventHandler} 구현체를 자동 주입하고,
 * eventType → Handler 매핑을 생성자에서 구축한다.
 * 새 이벤트 유형 추가 시 핸들러 빈만 등록하면 폴러 코드 수정이 불필요하다(OCP).</p>
 *
 * <h3>[Phase 6] FOR UPDATE SKIP LOCKED</h3>
 * <p>다중 인스턴스 배포 시 두 폴러가 동일한 이벤트를 동시에 읽어
 * 중복 처리하는 것을 방지한다. 한 폴러가 잠근 이벤트를 다른 폴러가 건너뛴다.</p>
 *
 * <h3>[Phase 15] 지수 백오프 재시도 & Dead Letter</h3>
 * <p><b>문제:</b> 기존 재시도는 5초 고정 간격으로 즉시 재시도하여, 외부 서비스 장애 시
 * 25초 안에 모든 재시도 기회를 소진했다. 또한 FAILED 이벤트는 수동 복구 경로가 없었다.</p>
 * <p><b>해결:</b> 지수 백오프(10s→20s→40s→80s→160s)로 재시도 간격을 점진적으로 늘려
 * 일시적 장애에 충분한 회복 시간을 부여한다. MAX_RETRIES 초과 시 DEAD_LETTER로 전이하여
 * 관리자가 OutboxDeadLetterService를 통해 원인 확인 후 재시도할 수 있다.
 * 실패 원인(lastError)을 DB에 저장하여 로그 검색 없이 진단이 가능하다.</p>
 *
 * <h3>at-least-once 보장</h3>
 * <p>이벤트 처리 후 PROCESSED로 전이하기 전에 크래시하면 다음 폴링에서
 * 동일 이벤트가 재처리된다. 따라서 핸들러는 멱등(idempotent)해야 한다.</p>
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
    private final Map<String, OutboxEventHandler> handlerMap;
    private final OutboxMetrics outboxMetrics;
    private final int maxRetries;
    private final int batchSize;
    private final int retryBaseDelaySec;

    /**
     * [Phase 6] Strategy 패턴: Spring이 주입한 모든 OutboxEventHandler를
     * eventType → Handler 매핑으로 변환한다.
     *
     * <p>[Phase 15] OutboxMetrics 의존성 추가: 처리/재시도/Dead Letter 카운터를
     * 기록하여 Prometheus 대시보드에서 Outbox 상태를 모니터링할 수 있다.</p>
     */
    public OutboxEventPoller(OutboxEventRepository outboxEventRepository,
                              List<OutboxEventHandler> handlers,
                              OutboxMetrics outboxMetrics,
                              @Value("${app.outbox.max-retries:5}") int maxRetries,
                              @Value("${app.outbox.batch-size:100}") int batchSize,
                              @Value("${app.outbox.retry-base-delay-sec:10}") int retryBaseDelaySec) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(
                        OutboxEventHandler::supportedEventType,
                        Function.identity()));
        this.outboxMetrics = outboxMetrics;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
        this.retryBaseDelaySec = retryBaseDelaySec;
        log.info("Outbox 폴러 초기화 - 등록된 핸들러: {}, maxRetries={}, baseDelay={}s",
                handlerMap.keySet(), maxRetries, retryBaseDelaySec);
    }

    /**
     * PENDING 이벤트를 폴링하여 처리한다.
     *
     * <p>각 이벤트를 개별 처리하고, 하나의 실패가 다른 이벤트 처리를
     * 중단하지 않도록 이벤트 단위로 예외를 catch한다.
     * 전체 메서드가 하나의 트랜잭션으로 실행되어, 모든 상태 전이가
     * 원자적으로 커밋된다.</p>
     *
     * <p>[Phase 6] findPendingEventsForUpdate(FOR UPDATE SKIP LOCKED)를 사용하여
     * 다중 폴러 간 이벤트 중복 처리를 방지한다.</p>
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollAndProcess() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForUpdate(batchSize);
        if (events.isEmpty()) {
            return;
        }

        int processed = 0;
        int deadLettered = 0;
        int retried = 0;

        for (OutboxEvent event : events) {
            try {
                processEvent(event);
                event.markProcessed();
                outboxMetrics.recordProcessed();
                processed++;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                // [Phase 15] 지수 백오프: 재시도 횟수에 따라 대기 시간이 기하급수적으로 증가한다.
                // MAX_RETRIES 도달 시 DEAD_LETTER로 전이하여 수동 개입을 유도한다.
                if (event.getRetryCount() + 1 >= maxRetries) {
                    event.moveToDeadLetter(errorMsg);
                    outboxMetrics.recordDeadLetter();
                    deadLettered++;
                    log.error("Outbox 이벤트 Dead Letter 전이 ({}회 재시도 초과) - eventId={}, type={}, error={}",
                            maxRetries, event.getEventId(), event.getEventType(), errorMsg, e);
                } else {
                    event.scheduleRetry(errorMsg, retryBaseDelaySec);
                    outboxMetrics.recordRetry();
                    retried++;
                    log.warn("Outbox 이벤트 재시도 예약 (시도 {}/{}, 다음 재시도={}) - eventId={}, type={}",
                            event.getRetryCount(), maxRetries, event.getNextRetryAt(),
                            event.getEventId(), event.getEventType(), e);
                }
            }
        }

        if (processed > 0 || deadLettered > 0 || retried > 0) {
            log.info("Outbox 폴링 완료 - processed={}, deadLettered={}, retried={}, total={}",
                    processed, deadLettered, retried, events.size());
        }
    }

    /**
     * [Phase 6] Strategy 패턴으로 이벤트를 라우팅한다.
     *
     * <p>기존 switch 문 대신 handlerMap에서 핸들러를 조회하여 실행한다.
     * 알 수 없는 이벤트 유형은 경고 로그를 남기고 정상 종료한다
     * (PROCESSED로 전이하여 다음 폴링에서 재처리되지 않도록 한다).</p>
     */
    private void processEvent(OutboxEvent event) {
        OutboxEventHandler handler = handlerMap.get(event.getEventType());
        if (handler == null) {
            log.warn("알 수 없는 Outbox 이벤트 유형: {} - eventId={}", event.getEventType(), event.getEventId());
            return;
        }
        handler.handle(event);
    }
}
