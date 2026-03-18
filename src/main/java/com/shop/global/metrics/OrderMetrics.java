package com.shop.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * [Phase 13] 주문 도메인 커스텀 메트릭.
 *
 * <h3>왜 HTTP 메트릭만으로 부족한가?</h3>
 * <p>Micrometer의 {@code http_server_requests_seconds}는 HTTP 요청 전체 시간을 측정하지만,
 * 주문 생성 내부의 비즈니스 로직 시간을 세분화하지 못한다.
 * 주문 생성은 비관적 잠금(재고 차감) + 쿠폰/포인트 처리 + Outbox 이벤트 발행을
 * 하나의 트랜잭션에서 수행하므로, 이 트랜잭션의 실행 시간과 성공/실패 비율을
 * 별도로 추적해야 동시성 병목을 식별할 수 있다.</p>
 *
 * <h3>등록되는 메트릭</h3>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.order.creation.duration</td><td>Timer</td>
 *       <td>주문 생성 트랜잭션 소요 시간 (히스토그램 포함)</td></tr>
 *   <tr><td>shop.order.creation.total</td><td>Counter</td>
 *       <td>주문 생성 시도 횟수 (result=success|failure 태그)</td></tr>
 * </table>
 *
 * <p><b>Grafana 활용 예시:</b></p>
 * <ul>
 *   <li>주문 p95 소요 시간이 특정 시점에 급증하면 → 잠금 경합 또는 커넥션 풀 대기 의심</li>
 *   <li>failure 카운터가 증가하면 → 재고 부족, 잠금 타임아웃, 쿠폰 만료 등 원인별 분석</li>
 * </ul>
 */
@Component
public class OrderMetrics {

    private final Timer orderCreationTimer;
    private final Counter orderSuccessCounter;
    private final Counter orderFailureCounter;

    public OrderMetrics(MeterRegistry registry) {
        // 주문 생성 소요 시간 — 비관적 잠금 대기를 포함한 트랜잭션 전체 시간
        this.orderCreationTimer = Timer.builder("shop.order.creation.duration")
                .description("주문 생성 트랜잭션 소요 시간 (잠금 대기 포함)")
                .publishPercentileHistogram()
                .register(registry);

        // 성공 카운터
        this.orderSuccessCounter = Counter.builder("shop.order.creation.total")
                .description("주문 생성 시도 횟수")
                .tag("result", "success")
                .register(registry);

        // 실패 카운터
        this.orderFailureCounter = Counter.builder("shop.order.creation.total")
                .description("주문 생성 시도 횟수")
                .tag("result", "failure")
                .register(registry);
    }

    /**
     * 주문 생성 타이머의 {@link Timer.Sample}을 시작한다.
     * 호출 시점부터 {@link #recordSuccess(Timer.Sample)} 또는
     * {@link #recordFailure(Timer.Sample)}까지의 경과 시간이 기록된다.
     */
    public Timer.Sample startTimer() {
        return Timer.start();
    }

    /**
     * 주문 생성 성공 — 타이머 기록 + 성공 카운터 증가.
     */
    public void recordSuccess(Timer.Sample sample) {
        sample.stop(orderCreationTimer);
        orderSuccessCounter.increment();
    }

    /**
     * 주문 생성 실패 — 타이머 기록 + 실패 카운터 증가.
     */
    public void recordFailure(Timer.Sample sample) {
        sample.stop(orderCreationTimer);
        orderFailureCounter.increment();
    }
}
