package com.shop.global.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 크로스 도메인 서비스 호출에 Retry + Timeout + Circuit Breaker를 적용하는 실행기.
 *
 * <h3>문제</h3>
 * <p>모놀리스 내에서 주문 도메인이 장바구니·사용자·쿠폰 서비스를 동기 호출할 때,
 * 호출 대상 서비스의 DB 쿼리 지연이나 장애가 호출자의 Tomcat 스레드를 점유하여
 * 전체 시스템으로 장애가 전파(cascading failure)될 수 있다.</p>
 *
 * <h3>해결</h3>
 * <p>세 가지 Resilience4j 패턴을 조합하여 장애를 격리한다:</p>
 * <ol>
 *   <li><b>Retry</b> — 일시적(transient) 실패 시 지수 백오프(exponential backoff)로
 *       자동 재시도한다. DB 커넥션 풀 일시 고갈, 네트워크 끊김 등에 효과적이다.</li>
 *   <li><b>CircuitBreaker</b> — 연속 실패(타임아웃 포함)를 추적하여 실패율이
 *       임계값을 초과하면 서킷을 OPEN하여 이후 호출을 즉시 거부(fail-fast)한다.</li>
 *   <li><b>TimeLimiter</b> — 호출을 별도 워커 스레드에서 실행하고 지정 시간 내
 *       응답이 없으면 {@link TimeoutException}을 발생시켜 호출자 스레드를 즉시 해제한다.</li>
 * </ol>
 *
 * <h3>적용 범위</h3>
 * <ul>
 *   <li><b>읽기 전용(read-only)</b> 크로스 도메인 호출에 사용한다.
 *       워커 스레드는 호출자의 트랜잭션 컨텍스트를 공유하지 않으므로,
 *       각 호출 대상 서비스가 자체 읽기 트랜잭션을 생성한다.</li>
 *   <li><b>쓰기(write)</b> 경로에는 사용하지 않는다.
 *       쓰기 작업은 트랜잭션 정합성이 필수이며, DB 레벨 타임아웃
 *       (socketTimeout=30s, lock_timeout=5s)이 안전망 역할을 한다.
 *       쓰기 경로에는 {@code @Retry} + {@code @CircuitBreaker} 어노테이션을 사용한다.</li>
 * </ul>
 *
 * <h3>호출 순서 (외부 → 내부)</h3>
 * <pre>
 *   Retry → CircuitBreaker → TimeLimiter → [워커 스레드에서] 실제 서비스 호출
 *
 *   - 일시적 실패 시: 실패 → CB 기록 → Retry가 재시도 → 성공 시 정상 반환
 *   - 서킷 OPEN 시: CB가 즉시 CallNotPermittedException
 *                    → Retry의 ignoreExceptions에 등록되어 재시도 없이 즉시 전파
 *   - 타임아웃 시: TL이 TimeoutException → CB 실패 기록 → Retry가 재시도
 *   - 재시도 소진 시: 최종 예외가 호출자에게 전파
 * </pre>
 *
 * <h3>SecurityContext 전파</h3>
 * <p>{@link DelegatingSecurityContextExecutorService}로 워커 스레드에
 * SecurityContext를 자동 전파하여, 인증 정보가 필요한 서비스 호출도
 * 타임아웃을 적용할 수 있다.</p>
 *
 * @see Resilience4jConfig 서킷 브레이커/리트라이 이벤트 로깅 및 메트릭 설정
 */
@Component
public class ResilientCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientCallExecutor.class);

    /** 서킷 브레이커 인스턴스를 관리하는 레지스트리. */
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 타임리미터 인스턴스를 관리하는 레지스트리. */
    private final TimeLimiterRegistry timeLimiterRegistry;

    /** 리트라이 인스턴스를 관리하는 레지스트리. 인스턴스별 재시도 횟수/백오프를 관리한다. */
    private final RetryRegistry retryRegistry;

    /**
     * 타임아웃 감시를 위한 워커 스레드 풀.
     *
     * <p>실제 서비스 호출을 이 스레드 풀에서 실행하고, 호출자 스레드는
     * Future.get(timeout)으로 결과를 대기한다.
     * 타임아웃 초과 시 호출자 스레드는 즉시 해제되고,
     * 워커 스레드는 DB 레벨 타임아웃에 의해 최종적으로 해제된다.</p>
     */
    private final ExecutorService executorService;

    /**
     * [Phase 20] 병렬 비동기 호출 조율용 가상 스레드 실행기.
     *
     * <p>executeAsync()에서 execute()를 비동기로 실행할 때 사용한다.
     * 가상 스레드는 블로킹 시 캐리어 스레드를 반환하므로,
     * 다수의 병렬 호출을 최소 리소스로 조율할 수 있다.</p>
     *
     * <p>스레드 사용량: 가상 스레드 1개(경량) + 워커 풀 플랫폼 스레드 1개(실제 JDBC 호출)
     * = execute()의 동기 호출과 동일한 워커 풀 부하.</p>
     */
    private final ExecutorService asyncCoordinator;

    public ResilientCallExecutor(CircuitBreakerRegistry circuitBreakerRegistry,
                                 TimeLimiterRegistry timeLimiterRegistry,
                                 RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.retryRegistry = retryRegistry;

        // SecurityContext를 워커 스레드에 자동 전파하는 ExecutorService.
        // 인증 정보가 필요한 서비스 호출(@PreAuthorize 등)도 타임아웃 적용이 가능하다.
        this.executorService = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors(),
                        runnable -> {
                            Thread thread = new Thread(runnable, "resilience-worker");
                            // 데몬 스레드: JVM 종료 시 이 스레드가 남아있어도 종료를 지연시키지 않는다.
                            thread.setDaemon(true);
                            return thread;
                        }
                )
        );

        // [Phase 20] 병렬 비동기 조율용 가상 스레드 실행기.
        // SecurityContext를 자동 전파하여, 인증 정보가 필요한 서비스 호출도
        // executeAsync()로 병렬 실행할 수 있다.
        this.asyncCoordinator = new DelegatingSecurityContextExecutorService(
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    /**
     * Retry + Timeout + Circuit Breaker를 적용하여 서비스를 호출한다.
     *
     * <p>데코레이션 순서: Retry(외부) → CircuitBreaker → TimeLimiter(내부)</p>
     * <ul>
     *   <li>일시적 실패(TimeoutException, DataAccessException 등)는 자동 재시도</li>
     *   <li>비즈니스 예외(BusinessException)는 재시도 없이 즉시 전파</li>
     *   <li>서킷 OPEN(CallNotPermittedException)은 재시도 없이 즉시 전파</li>
     * </ul>
     *
     * @param instanceName 인스턴스 이름 (application.yml에 정의)
     * @param supplier     실행할 서비스 호출
     * @param <T>          반환 타입
     * @return 서비스 호출 결과
     * @throws CallNotPermittedException 서킷이 OPEN 상태일 때 (즉시 실패)
     * @throws RuntimeException          재시도 소진 후 최종 실패 시
     */
    public <T> T execute(String instanceName, Supplier<T> supplier) {
        // 1) 레지스트리에서 이름으로 인스턴스를 조회한다.
        //    YAML에 정의되지 않은 이름이면 default 설정으로 자동 생성된다.
        Retry retry = retryRegistry.retry(instanceName);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter(instanceName);

        // 2) 실제 호출을 워커 스레드에서 비동기로 실행하는 Future 공급자를 생성한다.
        Supplier<CompletableFuture<T>> futureSupplier =
                () -> CompletableFuture.supplyAsync(supplier, executorService);

        // 3) TimeLimiter로 Future에 타임아웃을 적용한다 (가장 내부).
        Callable<T> timeLimited = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        // 4) CircuitBreaker로 타임리미터를 감싼다.
        //    서킷 OPEN 시 timeLimited.call()에 도달하지 않고 즉시 CallNotPermittedException.
        Callable<T> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, timeLimited);

        // 5) Retry로 서킷 브레이커를 감싼다 (가장 외부).
        //    실패 시 지수 백오프 후 재시도한다.
        //    CallNotPermittedException은 ignoreExceptions에 등록되어 재시도하지 않는다.
        Callable<T> withRetry = Retry.decorateCallable(retry, withCircuitBreaker);

        try {
            return withRetry.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Checked exception(TimeoutException 등)을 RuntimeException으로 래핑한다.
            throw new RuntimeException(e);
        }
    }

    /**
     * [Phase 20] execute()의 비동기 버전 — 결과를 CompletableFuture로 반환한다.
     *
     * <p>여러 크로스 도메인 호출을 병렬 실행할 때 사용한다.
     * 가상 스레드에서 execute()를 호출하므로, 동기 호출과 동일한
     * Retry + CircuitBreaker + TimeLimiter 보호를 받으면서도
     * 호출자 스레드를 블로킹하지 않는다.</p>
     *
     * <p>사용 예시 (3개 서비스 호출을 병렬 실행):</p>
     * <pre>
     *   CompletableFuture&lt;A&gt; fa = executor.executeAsync("svcA", () -&gt; svcA.call());
     *   CompletableFuture&lt;B&gt; fb = executor.executeAsync("svcB", () -&gt; svcB.call());
     *   CompletableFuture.allOf(fa, fb).join();
     *   A a = fa.join(); B b = fb.join();
     * </pre>
     *
     * @param instanceName 인스턴스 이름 (application.yml에 정의)
     * @param supplier     실행할 서비스 호출
     * @param <T>          반환 타입
     * @return 서비스 호출 결과를 담은 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsync(String instanceName, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(
                () -> execute(instanceName, supplier),
                asyncCoordinator
        );
    }

    /**
     * [Phase 20] executeWithFallback()의 비동기 버전.
     *
     * <p>비필수(non-critical) 서비스 호출을 병렬 실행할 때 사용한다.
     * 최종 실패 시 폴백 결과가 CompletableFuture에 담겨 반환되므로,
     * allOf().join()이 항상 성공한다.</p>
     *
     * @param instanceName 인스턴스 이름
     * @param supplier     실행할 서비스 호출
     * @param fallback     최종 실패 시 대체 결과를 생성하는 함수
     * @param <T>          반환 타입
     * @return 서비스 호출 결과 또는 폴백 결과를 담은 CompletableFuture
     */
    public <T> CompletableFuture<T> executeAsyncWithFallback(String instanceName,
                                                              Supplier<T> supplier,
                                                              Function<Exception, T> fallback) {
        return CompletableFuture.supplyAsync(
                () -> executeWithFallback(instanceName, supplier, fallback),
                asyncCoordinator
        );
    }

    /**
     * Retry + Timeout + Circuit Breaker를 적용하되, 최종 실패 시 폴백으로 대체한다.
     *
     * <p>비필수(non-critical) 서비스 호출에 사용한다.
     * 재시도를 모두 소진한 후에도 실패하면 폴백 함수가 실행된다.</p>
     *
     * @param instanceName 인스턴스 이름
     * @param supplier     실행할 서비스 호출
     * @param fallback     최종 실패 시 대체 결과를 생성하는 함수 (예외를 인자로 받음)
     * @param <T>          반환 타입
     * @return 서비스 호출 결과 또는 폴백 결과
     */
    public <T> T executeWithFallback(String instanceName, Supplier<T> supplier,
                                     Function<Exception, T> fallback) {
        try {
            return execute(instanceName, supplier);
        } catch (CallNotPermittedException e) {
            // 서킷 OPEN: 서비스가 장애 상태이므로 폴백으로 즉시 대체한다.
            log.info("[CircuitBreaker] '{}' 서킷 OPEN — 폴백 실행", instanceName);
            return fallback.apply(e);
        } catch (RuntimeException e) {
            // 재시도 소진 후 최종 실패: 폴백으로 대체한다.
            if (isTimeoutException(e)) {
                log.warn("[TimeLimiter] '{}' 재시도 소진 + 타임아웃 — 폴백 실행", instanceName);
            } else {
                log.warn("[ResilientCallExecutor] '{}' 재시도 소진 + 호출 실패 — 폴백 실행. error={}",
                        instanceName, e.getMessage());
            }
            return fallback.apply(e);
        }
    }

    /**
     * 예외가 타임아웃에 의한 것인지 확인한다.
     * TimeLimiter가 발생시키는 TimeoutException은 RuntimeException으로 래핑될 수 있다.
     */
    private boolean isTimeoutException(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 애플리케이션 종료 시 스레드 풀을 정리한다.
     * graceful shutdown: 5초 대기 후 미완료 시 강제 종료.
     */
    @PreDestroy
    void shutdown() {
        executorService.shutdown();
        asyncCoordinator.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
