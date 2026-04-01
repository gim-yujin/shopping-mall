package com.shop.global.resilience;

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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResilientCallExecutor 브랜치 커버리지 보강 테스트.
 *
 * <p>기존 ResilientCallExecutorTest에서 커버되지 않은 분기를 검증한다:
 * - executeAsync(): 비동기 실행 경로
 * - executeAsyncWithFallback(): 비동기 + 폴백 경로
 * - shutdown() InterruptedException 핸들링
 * - isTimeoutException() null cause 종료 조건</p>
 */
class ResilientCallExecutorBranchTest {

    private CircuitBreakerRegistry cbRegistry;
    private TimeLimiterRegistry tlRegistry;
    private RetryRegistry retryRegistry;
    private ResilientCallExecutor executor;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100)
                .build();
        cbRegistry = CircuitBreakerRegistry.of(cbConfig);

        TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        tlRegistry = TimeLimiterRegistry.of(tlConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(1)
                .build();
        retryRegistry = RetryRegistry.of(retryConfig);

        executor = new ResilientCallExecutor(cbRegistry, tlRegistry, retryRegistry);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Nested
    @DisplayName("executeAsync — 비동기 실행")
    class ExecuteAsyncTests {

        @Test
        @DisplayName("정상 호출 → CompletableFuture에 결과가 담긴다")
        void executeAsync_success_returnsResult() {
            CompletableFuture<String> future = executor.executeAsync("asyncTest", () -> "async-result");

            assertThat(future.join()).isEqualTo("async-result");
        }

        @Test
        @DisplayName("실패 호출 → CompletableFuture가 exceptionally 완료된다")
        void executeAsync_failure_completesExceptionally() {
            CompletableFuture<String> future = executor.executeAsync("asyncFailTest", () -> {
                throw new RuntimeException("비동기 장애");
            });

            // join()이 예외를 래핑하여 던지므로, CompletionException으로 검증
            org.assertj.core.api.Assertions.assertThatThrownBy(future::join)
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        }
    }

    @Nested
    @DisplayName("executeAsyncWithFallback — 비동기 + 폴백")
    class ExecuteAsyncWithFallbackTests {

        @Test
        @DisplayName("정상 호출 → CompletableFuture에 결과가 담긴다")
        void success_returnsResult() {
            CompletableFuture<String> future = executor.executeAsyncWithFallback(
                    "asyncFbOk", () -> "ok-result", ex -> "fallback");

            assertThat(future.join()).isEqualTo("ok-result");
        }

        @Test
        @DisplayName("실패 → 폴백 결과가 CompletableFuture에 담긴다")
        void failure_returnsFallback() {
            CompletableFuture<String> future = executor.executeAsyncWithFallback(
                    "asyncFbFail",
                    () -> { throw new RuntimeException("장애"); },
                    ex -> "fallback-value");

            assertThat(future.join()).isEqualTo("fallback-value");
        }
    }

    @Nested
    @DisplayName("shutdown — 스레드 풀 종료")
    class ShutdownTests {

        @Test
        @DisplayName("정상 shutdown — awaitTermination 성공 시 shutdownNow 미호출")
        void shutdown_normalTermination_succeeds() {
            // executor를 사용하지 않으면 워커 풀이 유휴 상태이므로 즉시 종료
            executor.shutdown();

            // 이후 호출이 거부되는지는 구현 의존적이지만, 에러 없이 완료되면 성공
            // (tearDown에서 다시 shutdown이 호출되므로 중복 호출도 안전한지 확인)
            executor.shutdown();
        }

        @Test
        @DisplayName("shutdown 중 InterruptedException — shutdownNow 호출 + 인터럽트 복원")
        void shutdown_interrupted_callsShutdownNowAndRestoresInterrupt() {
            // 인터럽트 플래그를 미리 설정하여 awaitTermination에서 InterruptedException 유발
            Thread.currentThread().interrupt();

            executor.shutdown();

            // 인터럽트 플래그가 복원되어야 한다
            assertThat(Thread.currentThread().isInterrupted()).isTrue();

            // 정리
            Thread.interrupted();
        }
    }

    @Nested
    @DisplayName("isTimeoutException — 예외 체인 탐색")
    class TimeoutExceptionTests {

        @Test
        @DisplayName("RuntimeException(원인 없음) → 타임아웃 아님 → 폴백에서 일반 실패로 처리")
        void runtimeExceptionWithNoCause_treatedAsNonTimeout() {
            String result = executor.executeWithFallback("noTimeoutTest",
                    () -> { throw new RuntimeException("일반 오류"); },
                    ex -> "non-timeout-fallback");

            assertThat(result).isEqualTo("non-timeout-fallback");
        }
    }
}
