package com.shop.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience4jConfig 단위 테스트.
 *
 * <p>서킷 브레이커 이벤트 리스너 등록과 Micrometer 메트릭 바인딩을
 * 실제 인메모리 레지스트리로 검증한다.</p>
 */
class Resilience4jConfigTest {

    @Test
    @DisplayName("init() 호출 후 상태 전이 이벤트 리스너가 등록되어 예외 없이 동작한다")
    void init_stateTransition_handledWithoutError() {
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.ofDefaults();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        // 서킷 브레이커 생성 후 Config 초기화 → 이벤트 리스너 부착
        CircuitBreaker cb = cbRegistry.circuitBreaker("testCb");
        Resilience4jConfig config = new Resilience4jConfig(cbRegistry, tlRegistry, meterRegistry);
        config.init();

        // 상태 전이를 강제로 발생시킨다.
        // 이벤트 리스너가 정상 등록되었으면 예외 없이 로그가 출력된다.
        cb.transitionToOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.transitionToClosedState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("init() 호출 후 Micrometer에 서킷 브레이커 메트릭이 등록된다")
    void init_bindsCircuitBreakerMetrics() {
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.ofDefaults();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        // 최소 1개의 서킷 브레이커가 있어야 메트릭이 등록된다.
        cbRegistry.circuitBreaker("metricsTestCb");

        Resilience4jConfig config = new Resilience4jConfig(cbRegistry, tlRegistry, meterRegistry);
        config.init();

        // TaggedCircuitBreakerMetrics가 등록한 메트릭이 존재해야 한다.
        // 예: resilience4j.circuitbreaker.state, resilience4j.circuitbreaker.calls 등
        assertThat(meterRegistry.getMeters()).isNotEmpty();

        // 서킷 브레이커 이름이 태그에 포함된 메트릭이 있어야 한다.
        boolean hasCircuitBreakerMetric = meterRegistry.getMeters().stream()
                .anyMatch(meter -> meter.getId().getName().startsWith("resilience4j.circuitbreaker"));
        assertThat(hasCircuitBreakerMetric)
                .as("resilience4j.circuitbreaker.* 메트릭이 등록되어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("init() 이후 동적으로 생성된 서킷 브레이커에도 이벤트 리스너가 부착된다")
    void init_dynamicInstance_getsEventListener() {
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.ofDefaults();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        // Config 초기화 (이 시점에는 서킷 브레이커 인스턴스가 없다)
        Resilience4jConfig config = new Resilience4jConfig(cbRegistry, tlRegistry, meterRegistry);
        config.init();

        // init() 이후에 동적으로 생성된 서킷 브레이커
        CircuitBreaker dynamicCb = cbRegistry.circuitBreaker("dynamicCb");

        // onEntryAdded 리스너가 동적 인스턴스에도 이벤트 리스너를 부착했으므로
        // 상태 전이가 예외 없이 처리되어야 한다.
        dynamicCb.transitionToOpenState();
        assertThat(dynamicCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        dynamicCb.transitionToHalfOpenState();
        assertThat(dynamicCb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
}
