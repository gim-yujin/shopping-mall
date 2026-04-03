package com.shop.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 서킷 브레이커 & 타임리미터 & 리트라이 설정.
 *
 * <h3>역할</h3>
 * <ul>
 *   <li><b>이벤트 로깅</b>: 서킷 상태 전이(CLOSED → OPEN → HALF_OPEN)와
 *       리트라이 이벤트(재시도/최종 실패)를 로그로 기록하여
 *       장애 격리 동작을 모니터링할 수 있게 한다.</li>
 *   <li><b>Micrometer 메트릭 바인딩</b>: 서킷 브레이커, 타임리미터, 리트라이의 호출 통계를
 *       Prometheus/Grafana에서 조회할 수 있도록 메트릭 레지스트리에 등록한다.</li>
 * </ul>
 *
 * <h3>인스턴스 설정</h3>
 * <p>각 인스턴스(cartService, userService, couponService, orderCreation)의
 * 실패율 임계값, 슬라이딩 윈도우, 느린 호출 기준 등은 {@code application.yml}의
 * {@code resilience4j.circuitbreaker.instances.*}에서 관리한다.</p>
 *
 * <h3>메트릭 키 예시 (Prometheus)</h3>
 * <pre>
 *   resilience4j_circuitbreaker_state{name="orderCreation"}          → 0/1/2 (CLOSED/OPEN/HALF_OPEN)
 *   resilience4j_circuitbreaker_calls_seconds_count{name="cartService", kind="successful"}
 *   resilience4j_timelimiter_calls_total{name="couponService", kind="timeout"}
 *   resilience4j_retry_calls_total{name="cartService", kind="successful_without_retry"}
 * </pre>
 */
@Configuration
public class Resilience4jConfig {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jConfig.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final RetryRegistry retryRegistry;
    private final MeterRegistry meterRegistry;

    public Resilience4jConfig(CircuitBreakerRegistry circuitBreakerRegistry,
                              TimeLimiterRegistry timeLimiterRegistry,
                              RetryRegistry retryRegistry,
                              MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.retryRegistry = retryRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 애플리케이션 시작 시 서킷 브레이커 이벤트 리스너와 메트릭을 등록한다.
     *
     * <p>서킷 상태 전이 이벤트를 로그로 기록하여 운영 중 장애 격리 동작을
     * 실시간으로 파악할 수 있다. 상태별 의미:</p>
     * <ul>
     *   <li><b>CLOSED → OPEN</b>: 실패율/느린 호출율 임계값 초과 → 모든 호출을 즉시 거부(fail-fast)</li>
     *   <li><b>OPEN → HALF_OPEN</b>: 대기 시간 경과 → 시험 호출 허용하여 복구 여부 확인</li>
     *   <li><b>HALF_OPEN → CLOSED</b>: 시험 호출 성공 → 정상 상태로 복귀</li>
     *   <li><b>HALF_OPEN → OPEN</b>: 시험 호출 실패 → 다시 차단</li>
     * </ul>
     */
    @PostConstruct
    void init() {
        registerCircuitBreakerEventListeners();
        registerRetryEventListeners();
        bindMetrics();
    }

    /**
     * 모든 서킷 브레이커 인스턴스에 상태 전이 이벤트 리스너를 등록한다.
     *
     * <p>기존 인스턴스와 이후 동적으로 생성되는 인스턴스 모두에 적용된다.
     * {@code getEventPublisher().onStateTransition()}은 서킷 상태가 바뀔 때만
     * 호출되므로, 정상 운영 시에는 로그가 발생하지 않는다.</p>
     */
    private void registerCircuitBreakerEventListeners() {
        // 기존에 YAML로 등록된 인스턴스에 리스너 부착
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::addStateTransitionLogger);

        // 이후 동적으로 생성되는 인스턴스(예: 새 서비스 추가)에도 자동 부착
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> addStateTransitionLogger(event.getAddedEntry()));
    }

    /**
     * 개별 서킷 브레이커에 상태 전이 로거를 부착한다.
     *
     * <p>WARN 레벨로 기록하여 운영 환경에서 로그 검색/알림 설정이 용이하게 한다.
     * 서킷 이름과 전이 방향(from → to)을 포함하여 어떤 서비스에서 장애 격리가
     * 발동되었는지 즉시 파악할 수 있다.</p>
     */
    private void addStateTransitionLogger(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "[CircuitBreaker] '{}' 상태 전이: {} → {}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()
                ));
    }

    // ── Retry 이벤트 리스너 ─────────────────────────────────────

    /**
     * 모든 Retry 인스턴스에 재시도/실패 이벤트 리스너를 등록한다.
     *
     * <p>기존 인스턴스와 이후 동적으로 생성되는 인스턴스 모두에 적용된다.</p>
     * <ul>
     *   <li><b>onRetry</b>: 재시도가 발생할 때마다 로그를 기록한다.
     *       어떤 예외로 인해 몇 번째 재시도인지 확인할 수 있다.</li>
     *   <li><b>onError</b>: 모든 재시도를 소진한 후 최종 실패 시 로그를 기록한다.
     *       이 로그가 빈번히 발생하면 인프라 장애가 지속 중임을 의미한다.</li>
     * </ul>
     */
    private void registerRetryEventListeners() {
        // 기존 인스턴스에 리스너 부착
        retryRegistry.getAllRetries()
                .forEach(this::addRetryLogger);

        // 동적으로 생성되는 인스턴스에도 자동 부착
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> addRetryLogger(event.getAddedEntry()));
    }

    /**
     * 개별 Retry 인스턴스에 재시도/실패 로거를 부착한다.
     *
     * <p>onRetry는 WARN 레벨: 일시적 장애가 발생했지만 아직 재시도 중임을 나타낸다.
     * onError는 ERROR 레벨: 모든 재시도를 소진한 최종 실패로, 즉각적인 조치가 필요하다.</p>
     */
    private void addRetryLogger(Retry retry) {
        retry.getEventPublisher()
                .onRetry(event -> {
                    Throwable lastThrowable = event.getLastThrowable();
                    log.warn(
                            "[Retry] '{}' 재시도 #{} — 원인: {}",
                            event.getName(),
                            event.getNumberOfRetryAttempts(),
                            lastThrowable != null ? lastThrowable.getMessage() : "unknown"
                    );
                })
                .onError(event -> {
                    Throwable lastThrowable = event.getLastThrowable();
                    log.error(
                            "[Retry] '{}' 재시도 소진 ({}회) — 최종 실패: {}",
                            event.getName(),
                            event.getNumberOfRetryAttempts(),
                            lastThrowable != null ? lastThrowable.getMessage() : "unknown"
                    );
                });
    }

    // ── Micrometer 메트릭 바인딩 ──────────────────────────────

    /**
     * Resilience4j 메트릭을 Micrometer 레지스트리에 바인딩한다.
     *
     * <p>바인딩 후 Prometheus 엔드포인트({@code /actuator/prometheus})에서
     * 서킷 브레이커/타임리미터의 호출 수, 실패율, 응답 시간 분포 등을
     * 조회할 수 있다.</p>
     *
     * <p>기존 Actuator 설정({@code management.endpoints.web.exposure.include})에
     * {@code circuitbreakers, circuitbreakerevents}를 추가하면
     * REST API로도 서킷 상태를 실시간 조회할 수 있다.</p>
     */
    private void bindMetrics() {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
                .bindTo(meterRegistry);
        TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(timeLimiterRegistry)
                .bindTo(meterRegistry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry)
                .bindTo(meterRegistry);
    }
}
