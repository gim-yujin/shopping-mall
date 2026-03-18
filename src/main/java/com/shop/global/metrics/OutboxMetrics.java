package com.shop.global.metrics;

import com.shop.global.outbox.OutboxEvent;
import com.shop.global.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * [Phase 15] Outbox 이벤트 처리 커스텀 메트릭.
 *
 * <h3>왜 Outbox 메트릭이 필요한가?</h3>
 * <p>기존 Outbox 폴러는 로그만 남겼으므로, 이벤트 처리 지연이나 Dead Letter 누적을
 * 실시간으로 파악할 수 없었다. 메트릭이 없으면:</p>
 * <ul>
 *   <li>PENDING 이벤트가 누적되어도 알 수 없음 → 캐시 무효화/알림 지연 감지 불가</li>
 *   <li>Dead Letter 이벤트 발생을 로그 검색으로만 확인 → 대응 지연</li>
 *   <li>재시도 빈도를 정량적으로 파악 불가 → 외부 서비스 불안정성 추적 불가</li>
 * </ul>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.outbox.processed.total</td><td>Counter</td>
 *       <td>성공적으로 처리된 이벤트 누적 수</td></tr>
 *   <tr><td>shop.outbox.dead.letter.total</td><td>Counter</td>
 *       <td>Dead Letter로 전이된 이벤트 누적 수</td></tr>
 *   <tr><td>shop.outbox.retry.total</td><td>Counter</td>
 *       <td>재시도(백오프 스케줄링)된 이벤트 누적 수</td></tr>
 *   <tr><td>shop.outbox.pending.count</td><td>Gauge</td>
 *       <td>현재 PENDING 상태인 이벤트 수 (큐 깊이)</td></tr>
 *   <tr><td>shop.outbox.dead.letter.count</td><td>Gauge</td>
 *       <td>현재 DEAD_LETTER 상태인 이벤트 수</td></tr>
 * </table>
 */
@Component
public class OutboxMetrics {

    private final Counter processedCounter;
    private final Counter deadLetterCounter;
    private final Counter retryCounter;

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        this.processedCounter = Counter.builder("shop.outbox.processed.total")
                .description("성공적으로 처리된 Outbox 이벤트 누적 수")
                .register(registry);

        this.deadLetterCounter = Counter.builder("shop.outbox.dead.letter.total")
                .description("Dead Letter로 전이된 Outbox 이벤트 누적 수")
                .register(registry);

        this.retryCounter = Counter.builder("shop.outbox.retry.total")
                .description("재시도 스케줄링된 Outbox 이벤트 누적 수")
                .register(registry);

        // Gauge: Prometheus 스크래핑 시점의 PENDING 큐 깊이를 조회한다.
        // 폴러 지연이나 이벤트 폭증을 감지하기 위한 핵심 지표.
        Gauge.builder("shop.outbox.pending.count", repository,
                        repo -> repo.countByStatus(OutboxEvent.STATUS_PENDING))
                .description("현재 PENDING 상태인 Outbox 이벤트 수")
                .register(registry);

        // Gauge: Dead Letter 큐 깊이. 0이 아니면 관리자 개입이 필요한 상태.
        Gauge.builder("shop.outbox.dead.letter.count", repository,
                        repo -> repo.countByStatus(OutboxEvent.STATUS_DEAD_LETTER))
                .description("현재 DEAD_LETTER 상태인 Outbox 이벤트 수")
                .register(registry);
    }

    /** 이벤트가 성공적으로 처리된 경우. */
    public void recordProcessed() {
        processedCounter.increment();
    }

    /** 이벤트가 Dead Letter로 전이된 경우 (MAX_RETRIES 초과). */
    public void recordDeadLetter() {
        deadLetterCounter.increment();
    }

    /** 이벤트가 재시도 스케줄링된 경우 (지수 백오프 적용). */
    public void recordRetry() {
        retryCounter.increment();
    }
}
