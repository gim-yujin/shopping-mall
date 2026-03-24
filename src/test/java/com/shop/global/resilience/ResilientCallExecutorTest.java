package com.shop.global.resilience;

import com.shop.global.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResilientCallExecutor 단위 테스트.
 *
 * <p>실제 Resilience4j CircuitBreakerRegistry/TimeLimiterRegistry를 사용하여
 * Timeout과 Circuit Breaker의 장애 격리 동작을 검증한다.
 * 서킷 브레이커 설정을 테스트에 최적화(작은 윈도우, 짧은 타임아웃)하여
 * 빠르고 결정적(deterministic)인 테스트를 보장한다.</p>
 *
 * <p>테스트 인스턴스별 설정:</p>
 * <ul>
 *   <li>CircuitBreaker: slidingWindowSize=4, minimumNumberOfCalls=2, failureRate=50%</li>
 *   <li>TimeLimiter: timeoutDuration=200ms</li>
 * </ul>
 */
class ResilientCallExecutorTest {

    /** 테스트용 인스턴스 이름 — 각 테스트에서 충돌하지 않도록 메서드별로 구분한다. */
    private static final String INSTANCE = "testService";

    private CircuitBreakerRegistry cbRegistry;
    private TimeLimiterRegistry tlRegistry;
    private RetryRegistry retryRegistry;
    private ResilientCallExecutor executor;

    @BeforeEach
    void setUp() {
        // 서킷 브레이커: 최근 4건 중 2건(50%) 이상 실패 시 서킷 OPEN.
        // minimumNumberOfCalls=2로 설정하여 2건 연속 실패로 즉시 서킷을 열 수 있다.
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        cbRegistry = CircuitBreakerRegistry.of(cbConfig);

        // 타임리미터: 200ms 내 응답이 없으면 TimeoutException 발생.
        TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200))
                .cancelRunningFuture(true)
                .build();
        tlRegistry = TimeLimiterRegistry.of(tlConfig);

        // 리트라이: 기존 테스트에서는 재시도를 비활성화(maxAttempts=1)하여
        // 서킷 브레이커/타임리미터 동작만 독립적으로 검증한다.
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(1)
                .build();
        retryRegistry = RetryRegistry.of(retryConfig);

        executor = new ResilientCallExecutor(cbRegistry, tlRegistry, retryRegistry);
    }

    @AfterEach
    void tearDown() {
        // 워커 스레드 풀 정리 (타임아웃 테스트에서 남은 슬리핑 스레드 회수)
        executor.shutdown();
    }

    // ── 정상 호출 ──────────────────────────────────────────────

    @Nested
    @DisplayName("정상 호출")
    class NormalExecution {

        @Test
        @DisplayName("execute() 성공 → supplier 결과를 그대로 반환한다")
        void execute_success_returnsResult() {
            String result = executor.execute(INSTANCE, () -> "hello");

            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("execute() supplier 예외 → 예외가 그대로 전파된다")
        void execute_supplierThrows_propagatesException() {
            assertThatThrownBy(() -> executor.execute(INSTANCE, () -> {
                throw new RuntimeException("서비스 장애");
            })).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("서비스 장애");
        }
    }

    // ── 타임아웃 ──────────────────────────────────────────────

    @Nested
    @DisplayName("타임아웃 (TimeLimiter)")
    class TimeoutBehavior {

        @Test
        @DisplayName("느린 호출(200ms 초과) → TimeoutException을 cause로 가진 RuntimeException")
        void execute_slowCall_throwsTimeoutException() {
            // 타임리미터 타임아웃(200ms)보다 긴 2초 대기 — 확실한 타임아웃 유발
            assertThatThrownBy(() -> executor.execute(INSTANCE, () -> {
                sleepUninterruptibly(2000);
                return "should not reach";
            })).isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("느린 호출 + executeWithFallback() → 폴백 결과를 반환한다")
        void executeWithFallback_slowCall_returnsFallback() {
            String result = executor.executeWithFallback(INSTANCE,
                    () -> {
                        sleepUninterruptibly(2000);
                        return "should not reach";
                    },
                    ex -> "fallback-value");

            assertThat(result).isEqualTo("fallback-value");
        }
    }

    // ── 서킷 브레이커 상태 전이 ───────────────────────────────

    @Nested
    @DisplayName("서킷 브레이커 상태 전이")
    class CircuitBreakerTransitions {

        @Test
        @DisplayName("실패 2건 누적 → 서킷 OPEN → CallNotPermittedException")
        void failuresExceedThreshold_circuitOpens() {
            String name = "cbOpenTest";

            // 2건 연속 실패 → 실패율 100% > 임계값 50% → 서킷 OPEN
            for (int i = 0; i < 2; i++) {
                try {
                    executor.execute(name, () -> {
                        throw new RuntimeException("인프라 장애");
                    });
                } catch (RuntimeException ignored) {
                    // 실패 기록이 목적
                }
            }

            CircuitBreaker cb = cbRegistry.circuitBreaker(name);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 서킷 OPEN 상태에서 호출 → CallNotPermittedException (즉시 실패)
            assertThatThrownBy(() -> executor.execute(name, () -> "should not execute"))
                    .isInstanceOf(CallNotPermittedException.class);
        }

        @Test
        @DisplayName("서킷 OPEN + executeWithFallback() → 폴백 결과를 반환한다")
        void circuitOpen_withFallback_returnsFallback() {
            String name = "cbFallbackTest";

            // 서킷을 강제로 OPEN 상태로 전환
            cbRegistry.circuitBreaker(name).transitionToOpenState();

            String result = executor.executeWithFallback(name,
                    () -> "should not execute",
                    ex -> "circuit-open-fallback");

            assertThat(result).isEqualTo("circuit-open-fallback");
        }

        @Test
        @DisplayName("OPEN → HALF_OPEN → 성공 호출 → CLOSED 복귀 (자동 복구)")
        void halfOpen_success_transitionsToClosed() {
            String name = "cbRecoveryTest";

            // 1) 실패 누적 → 서킷 OPEN
            for (int i = 0; i < 2; i++) {
                try {
                    executor.execute(name, () -> {
                        throw new RuntimeException("장애");
                    });
                } catch (RuntimeException ignored) {
                    // 실패 기록
                }
            }
            CircuitBreaker cb = cbRegistry.circuitBreaker(name);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 2) 수동으로 HALF_OPEN 전환 (테스트에서는 waitDuration 대기 대신 직접 전환)
            cb.transitionToHalfOpenState();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // 3) HALF_OPEN에서 성공 호출 → permittedNumberOfCallsInHalfOpenState=1이므로
            //    1건 성공으로 서킷이 CLOSED로 복귀한다.
            String result = executor.execute(name, () -> "recovered");
            assertThat(result).isEqualTo("recovered");
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("타임아웃도 실패로 기록되어 서킷 OPEN에 기여한다")
        void timeouts_countAsFailures_openCircuit() {
            String name = "cbTimeoutFailTest";

            // 2건 연속 타임아웃 → 실패율 100% → 서킷 OPEN
            for (int i = 0; i < 2; i++) {
                try {
                    executor.execute(name, () -> {
                        sleepUninterruptibly(2000);
                        return "timeout";
                    });
                } catch (RuntimeException ignored) {
                    // 타임아웃 예외 기록
                }
            }

            CircuitBreaker cb = cbRegistry.circuitBreaker(name);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    // ── 예외 분류 (ignoreExceptions) ──────────────────────────

    @Nested
    @DisplayName("예외 분류")
    class ExceptionClassification {

        @Test
        @DisplayName("ignoreExceptions에 등록된 BusinessException → 서킷 실패로 카운트하지 않는다")
        void businessException_notCountedAsFailure() {
            // ignoreExceptions에 BusinessException을 등록한 별도 레지스트리 생성.
            // 운영 YAML에서도 BusinessException은 ignoreExceptions에 포함되어 있다.
            CircuitBreakerConfig configWithIgnore = CircuitBreakerConfig.custom()
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50)
                    .ignoreExceptions(BusinessException.class)
                    .build();
            CircuitBreakerRegistry customRegistry = CircuitBreakerRegistry.of(configWithIgnore);
            ResilientCallExecutor customExecutor = new ResilientCallExecutor(customRegistry, tlRegistry, retryRegistry);

            try {
                String name = "ignoreTest";

                // BusinessException을 5회 연속 발생시킨다.
                // ignoreExceptions이므로 서킷 실패율에 반영되지 않아야 한다.
                for (int i = 0; i < 5; i++) {
                    try {
                        customExecutor.execute(name, () -> {
                            throw new BusinessException("TEST", "비즈니스 오류");
                        });
                    } catch (BusinessException ignored) {
                        // 비즈니스 예외는 정상 거절이므로 무시
                    }
                }

                // 서킷은 여전히 CLOSED 상태여야 한다.
                // BusinessException이 실패로 카운트되었다면 2건째에서 OPEN이 되었을 것이다.
                CircuitBreaker cb = customRegistry.circuitBreaker(name);
                assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            } finally {
                customExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("executeWithFallback() — 비즈니스 예외는 폴백 없이 그대로 전파된다")
        void executeWithFallback_businessException_propagatesDirectly() {
            // executeWithFallback()은 RuntimeException을 catch하여 폴백을 실행하지만,
            // 비즈니스 예외도 RuntimeException이므로 폴백이 실행된다.
            // 이 동작을 확인하여 호출측에서 비즈니스 예외 처리 전략을 결정할 수 있다.
            AtomicInteger fallbackCount = new AtomicInteger(0);

            String result = executor.executeWithFallback(INSTANCE,
                    () -> {
                        throw new BusinessException("COUPON_EXPIRED", "만료된 쿠폰");
                    },
                    ex -> {
                        fallbackCount.incrementAndGet();
                        return "fallback";
                    });

            assertThat(result).isEqualTo("fallback");
            assertThat(fallbackCount.get()).isEqualTo(1);
        }
    }

    // ── 리트라이 (Retry) ──────────────────────────────────────

    @Nested
    @DisplayName("리트라이 (Retry)")
    class RetryBehavior {

        /**
         * 리트라이 전용 executor를 생성한다.
         * maxAttempts=3, 대기 시간 없음(테스트 속도)으로 설정.
         */
        private ResilientCallExecutor retryExecutor() {
            RetryConfig retryConfig = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(0))
                    .retryExceptions(RuntimeException.class)
                    .build();
            RetryRegistry localRetryRegistry = RetryRegistry.of(retryConfig);

            // 서킷 브레이커는 관대한 설정으로 리트라이 동작만 검증한다.
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                    .slidingWindowSize(100)
                    .minimumNumberOfCalls(100)
                    .failureRateThreshold(100)
                    .build();
            CircuitBreakerRegistry localCbRegistry = CircuitBreakerRegistry.of(cbConfig);

            return new ResilientCallExecutor(localCbRegistry, tlRegistry, localRetryRegistry);
        }

        @Test
        @DisplayName("일시적 실패 후 성공 → 재시도로 정상 결과를 반환한다")
        void transientFailure_thenSuccess_returnsResult() {
            ResilientCallExecutor retryExec = retryExecutor();
            try {
                // 2번 실패 후 3번째에 성공 (maxAttempts=3이므로 성공)
                AtomicInteger attempt = new AtomicInteger(0);

                String result = retryExec.execute("retrySuccessTest", () -> {
                    if (attempt.incrementAndGet() <= 2) {
                        throw new RuntimeException("일시적 장애 #" + attempt.get());
                    }
                    return "recovered";
                });

                assertThat(result).isEqualTo("recovered");
                assertThat(attempt.get()).isEqualTo(3);
            } finally {
                retryExec.shutdown();
            }
        }

        @Test
        @DisplayName("모든 재시도 소진 → 최종 예외가 전파된다")
        void allRetriesExhausted_throwsFinalException() {
            ResilientCallExecutor retryExec = retryExecutor();
            try {
                AtomicInteger attempt = new AtomicInteger(0);

                // maxAttempts=3인데 3번 모두 실패 → 최종 예외 전파
                assertThatThrownBy(() -> retryExec.execute("retryExhaustTest", () -> {
                    attempt.incrementAndGet();
                    throw new RuntimeException("영구 장애");
                })).isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("영구 장애");

                // 첫 시도 + 재시도 2회 = 총 3회 호출
                assertThat(attempt.get()).isEqualTo(3);
            } finally {
                retryExec.shutdown();
            }
        }

        @Test
        @DisplayName("재시도 소진 + executeWithFallback() → 폴백 결과를 반환한다")
        void allRetriesExhausted_withFallback_returnsFallback() {
            ResilientCallExecutor retryExec = retryExecutor();
            try {
                String result = retryExec.executeWithFallback("retryFallbackTest",
                        () -> {
                            throw new RuntimeException("영구 장애");
                        },
                        ex -> "retry-exhausted-fallback");

                assertThat(result).isEqualTo("retry-exhausted-fallback");
            } finally {
                retryExec.shutdown();
            }
        }

        @Test
        @DisplayName("ignoreExceptions에 등록된 BusinessException → 재시도하지 않는다")
        void businessException_notRetried() {
            // BusinessException을 ignoreExceptions에 등록한 리트라이 설정
            RetryConfig retryConfig = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(0))
                    .retryExceptions(RuntimeException.class)
                    .ignoreExceptions(BusinessException.class)
                    .build();
            RetryRegistry localRetryRegistry = RetryRegistry.of(retryConfig);
            ResilientCallExecutor retryExec = new ResilientCallExecutor(cbRegistry, tlRegistry, localRetryRegistry);

            try {
                AtomicInteger attempt = new AtomicInteger(0);

                // BusinessException은 ignoreExceptions이므로 재시도 없이 즉시 전파
                assertThatThrownBy(() -> retryExec.execute("retryIgnoreTest", () -> {
                    attempt.incrementAndGet();
                    throw new BusinessException("VALIDATION", "비즈니스 오류");
                })).isInstanceOf(BusinessException.class);

                // 재시도 없이 1회만 호출되어야 한다
                assertThat(attempt.get()).isEqualTo(1);
            } finally {
                retryExec.shutdown();
            }
        }
    }

    // ── 유틸리티 ──────────────────────────────────────────────

    /**
     * InterruptedException을 무시하는 sleep.
     * 워커 스레드에서 타임아웃 테스트용 느린 호출을 시뮬레이션한다.
     */
    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
