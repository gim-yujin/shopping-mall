package com.shop.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * [Phase 14] 멱등성 키 패턴 커스텀 메트릭.
 *
 * <h3>왜 멱등성 메트릭이 필요한가?</h3>
 * <p>멱등성 키 패턴은 중복 주문을 방지하지만, 실제로 얼마나 많은 중복 요청이
 * 발생하는지, 서버 크래시로 인한 고착 복구가 얼마나 빈번한지를 로그만으로는
 * 정량적으로 파악하기 어렵다. Prometheus 카운터로 노출하면:</p>
 * <ul>
 *   <li>duplicate_completed 급증 → 클라이언트 재시도 로직 과다 또는 봇 공격 의심</li>
 *   <li>duplicate_processing 급증 → 주문 처리 지연 감지 (잠금 경합 가능성)</li>
 *   <li>conflict 급증 → 동시 중복 요청 폭증 (DDos 또는 프론트 버그)</li>
 *   <li>stale_recovered 발생 → 서버 비정상 종료 감지 (배포/OOM 등)</li>
 * </ul>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>태그</th><th>설명</th></tr>
 *   <tr><td>shop.idempotency.requests.total</td><td>result=new</td>
 *       <td>최초 요청 (PROCESSING 레코드 생성 성공)</td></tr>
 *   <tr><td>shop.idempotency.requests.total</td><td>result=duplicate_completed</td>
 *       <td>이미 완료된 요청의 중복 감지 (캐시 응답 반환)</td></tr>
 *   <tr><td>shop.idempotency.requests.total</td><td>result=duplicate_processing</td>
 *       <td>처리 중인 요청의 중복 감지 (409 Conflict 반환)</td></tr>
 *   <tr><td>shop.idempotency.requests.total</td><td>result=conflict</td>
 *       <td>UNIQUE 제약 위반에 의한 동시 중복 차단</td></tr>
 *   <tr><td>shop.idempotency.requests.total</td><td>result=retry</td>
 *       <td>FAILED 레코드 재시도</td></tr>
 *   <tr><td>shop.idempotency.stale.recovered.total</td><td>-</td>
 *       <td>PROCESSING 고착 → FAILED 자동 복구된 레코드 수</td></tr>
 * </table>
 */
@Component
public class IdempotencyMetrics {

    private final Counter newRequestCounter;
    private final Counter duplicateCompletedCounter;
    private final Counter duplicateProcessingCounter;
    private final Counter conflictCounter;
    private final Counter retryCounter;
    private final Counter staleRecoveredCounter;

    public IdempotencyMetrics(MeterRegistry registry) {
        this.newRequestCounter = Counter.builder("shop.idempotency.requests.total")
                .description("멱등성 키 요청 결과별 횟수")
                .tag("result", "new")
                .register(registry);

        this.duplicateCompletedCounter = Counter.builder("shop.idempotency.requests.total")
                .description("멱등성 키 요청 결과별 횟수")
                .tag("result", "duplicate_completed")
                .register(registry);

        this.duplicateProcessingCounter = Counter.builder("shop.idempotency.requests.total")
                .description("멱등성 키 요청 결과별 횟수")
                .tag("result", "duplicate_processing")
                .register(registry);

        this.conflictCounter = Counter.builder("shop.idempotency.requests.total")
                .description("멱등성 키 요청 결과별 횟수")
                .tag("result", "conflict")
                .register(registry);

        this.retryCounter = Counter.builder("shop.idempotency.requests.total")
                .description("멱등성 키 요청 결과별 횟수")
                .tag("result", "retry")
                .register(registry);

        // PROCESSING 고착 복구는 요청 흐름이 아닌 스케줄러에서 발생하므로 별도 메트릭으로 분리
        this.staleRecoveredCounter = Counter.builder("shop.idempotency.stale.recovered.total")
                .description("PROCESSING 고착 → FAILED 자동 복구된 레코드 수")
                .register(registry);
    }

    /** 최초 요청: PROCESSING 레코드 생성 성공. */
    public void recordNew() {
        newRequestCounter.increment();
    }

    /** 이미 완료된 요청의 중복 감지: 캐시된 응답 반환. */
    public void recordDuplicateCompleted() {
        duplicateCompletedCounter.increment();
    }

    /** 처리 중인 요청의 중복 감지: 409 Conflict 반환. */
    public void recordDuplicateProcessing() {
        duplicateProcessingCounter.increment();
    }

    /** UNIQUE 제약 위반에 의한 동시 중복 차단. */
    public void recordConflict() {
        conflictCounter.increment();
    }

    /** FAILED 레코드 재시도. */
    public void recordRetry() {
        retryCounter.increment();
    }

    /** PROCESSING 고착 → FAILED 자동 복구. count만큼 증가. */
    public void recordStaleRecovered(int count) {
        staleRecoveredCounter.increment(count);
    }
}
