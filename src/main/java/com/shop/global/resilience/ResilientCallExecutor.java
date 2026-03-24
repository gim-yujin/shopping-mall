package com.shop.global.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
 * 크로스 도메인 서비스 호출에 Timeout + Circuit Breaker를 적용하는 실행기.
 *
 * <h3>문제</h3>
 * <p>모놀리스 내에서 주문 도메인이 장바구니·사용자·쿠폰 서비스를 동기 호출할 때,
 * 호출 대상 서비스의 DB 쿼리 지연이나 장애가 호출자의 Tomcat 스레드를 점유하여
 * 전체 시스템으로 장애가 전파(cascading failure)될 수 있다.</p>
 *
 * <h3>해결</h3>
 * <p>두 가지 Resilience4j 패턴을 조합하여 장애를 격리한다:</p>
 * <ol>
 *   <li><b>TimeLimiter</b> — 호출을 별도 워커 스레드에서 실행하고 지정 시간 내
 *       응답이 없으면 {@link TimeoutException}을 발생시켜 호출자 스레드를 즉시 해제한다.</li>
 *   <li><b>CircuitBreaker</b> — 연속 실패(타임아웃 포함)를 추적하여 실패율이
 *       임계값을 초과하면 서킷을 OPEN하여 이후 호출을 즉시 거부(fail-fast)한다.
 *       이로써 이미 장애 중인 서비스에 불필요한 요청이 누적되는 것을 방지한다.</li>
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
 *       쓰기 경로에는 {@code @CircuitBreaker} 어노테이션을 직접 사용한다.</li>
 * </ul>
 *
 * <h3>호출 순서 (외부 → 내부)</h3>
 * <pre>
 *   CircuitBreaker → TimeLimiter → [워커 스레드에서] 실제 서비스 호출
 *
 *   - 서킷 OPEN 시: CircuitBreaker가 즉시 CallNotPermittedException 반환
 *                    → TimeLimiter/워커 스레드에 도달하지 않아 리소스 절약
 *   - 서킷 CLOSED 시: TimeLimiter가 워커 스레드의 Future를 감시
 *                     → 타임아웃 초과 시 TimeoutException 반환
 *                     → CircuitBreaker가 실패로 기록 → 누적 시 서킷 OPEN
 * </pre>
 *
 * <h3>SecurityContext 전파</h3>
 * <p>{@link DelegatingSecurityContextExecutorService}로 워커 스레드에
 * SecurityContext를 자동 전파하여, 인증 정보가 필요한 서비스 호출도
 * 타임아웃을 적용할 수 있다.</p>
 *
 * @see Resilience4jConfig 서킷 브레이커 이벤트 로깅 및 메트릭 설정
 */
@Component
public class ResilientCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientCallExecutor.class);

    /** 서킷 브레이커 인스턴스를 관리하는 레지스트리. application.yml에서 설정된 인스턴스를 제공한다. */
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 타임리미터 인스턴스를 관리하는 레지스트리. 인스턴스별 타임아웃 시간을 관리한다. */
    private final TimeLimiterRegistry timeLimiterRegistry;

    /**
     * 타임아웃 감시를 위한 워커 스레드 풀.
     *
     * <p>실제 서비스 호출을 이 스레드 풀에서 실행하고, 호출자 스레드는
     * Future.get(timeout)으로 결과를 대기한다.
     * 타임아웃 초과 시 호출자 스레드는 즉시 해제되고,
     * 워커 스레드는 DB 레벨 타임아웃에 의해 최종적으로 해제된다.</p>
     *
     * <p>스레드 수: CPU 코어 수만큼 할당. 읽기 전용 호출은 대부분 I/O 대기이므로
     * 코어 수로 충분하다. 필요 시 application.yml에서 설정 가능하도록 확장 가능.</p>
     */
    private final ExecutorService executorService;

    public ResilientCallExecutor(CircuitBreakerRegistry circuitBreakerRegistry,
                                 TimeLimiterRegistry timeLimiterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;

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
    }

    /**
     * Timeout + Circuit Breaker를 적용하여 서비스를 호출한다.
     *
     * <p>사용 예시:</p>
     * <pre>
     * List&lt;Cart&gt; items = resilientCallExecutor.execute("cartService",
     *         () -&gt; cartService.getSelectedCartItems(userId, cartItemIds));
     * </pre>
     *
     * @param instanceName 서킷 브레이커/타임리미터 인스턴스 이름 (application.yml에 정의)
     * @param supplier     실행할 서비스 호출
     * @param <T>          반환 타입
     * @return 서비스 호출 결과
     * @throws CallNotPermittedException 서킷이 OPEN 상태일 때 (즉시 실패)
     * @throws RuntimeException          타임아웃 또는 서비스 호출 실패 시
     */
    public <T> T execute(String instanceName, Supplier<T> supplier) {
        // 1) 레지스트리에서 이름으로 인스턴스를 조회한다.
        //    YAML에 정의되지 않은 이름이면 default 설정으로 자동 생성된다.
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter(instanceName);

        // 2) 실제 호출을 워커 스레드에서 비동기로 실행하는 Future 공급자를 생성한다.
        //    supplyAsync()는 executorService의 스레드에서 supplier를 실행한다.
        Supplier<CompletableFuture<T>> futureSupplier =
                () -> CompletableFuture.supplyAsync(supplier, executorService);

        // 3) TimeLimiter로 Future에 타임아웃을 적용한다.
        //    지정 시간 내 완료되지 않으면 TimeoutException을 던진다.
        Callable<T> timeLimited = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        // 4) CircuitBreaker로 타임리미터를 감싼다 (외부 → 내부: CB → TL → 실제 호출).
        //    서킷 OPEN 시 timeLimited.call()에 도달하지 않고 즉시 CallNotPermittedException.
        Callable<T> decorated = CircuitBreaker.decorateCallable(circuitBreaker, timeLimited);

        try {
            return decorated.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Checked exception을 RuntimeException으로 래핑한다.
            // TimeoutException, ExecutionException 등이 여기에 해당한다.
            throw new RuntimeException(e);
        }
    }

    /**
     * Timeout + Circuit Breaker를 적용하되, 실패 시 폴백 함수로 대체 결과를 반환한다.
     *
     * <p>비필수(non-critical) 서비스 호출에 사용한다.
     * 예를 들어, 쿠폰 서비스 장애 시 빈 목록을 반환하여 체크아웃을 계속 진행할 수 있다.</p>
     *
     * <pre>
     * // 쿠폰 서비스 장애 시 빈 목록으로 폴백 → 체크아웃 페이지는 쿠폰 없이 표시
     * List&lt;UserCoupon&gt; coupons = resilientCallExecutor.executeWithFallback(
     *         "couponService",
     *         () -&gt; couponService.getAvailableCoupons(userId),
     *         ex -&gt; {
     *             log.warn("쿠폰 서비스 장애, 빈 목록으로 폴백. reason={}", ex.getMessage());
     *             return Collections.emptyList();
     *         });
     * </pre>
     *
     * @param instanceName 서킷 브레이커/타임리미터 인스턴스 이름
     * @param supplier     실행할 서비스 호출
     * @param fallback     실패 시 대체 결과를 생성하는 함수 (예외를 인자로 받음)
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
            // 타임아웃 또는 서비스 호출 실패: 폴백으로 대체한다.
            if (isTimeoutException(e)) {
                log.warn("[TimeLimiter] '{}' 타임아웃 — 폴백 실행", instanceName);
            } else {
                log.warn("[ResilientCallExecutor] '{}' 호출 실패 — 폴백 실행. error={}",
                        instanceName, e.getMessage());
            }
            return fallback.apply(e);
        }
    }

    /**
     * 예외가 타임아웃에 의한 것인지 확인한다.
     *
     * <p>TimeLimiter가 발생시키는 TimeoutException은 RuntimeException으로 래핑되어
     * 전달될 수 있으므로, cause 체인을 확인한다.</p>
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
     * 애플리케이션 종료 시 워커 스레드 풀을 정리한다.
     *
     * <p>graceful shutdown: 먼저 새 작업 제출을 중단하고, 진행 중인 작업이
     * 5초 내에 완료되기를 기다린다. 5초 후에도 완료되지 않으면 강제 종료한다.</p>
     */
    @PreDestroy
    void shutdown() {
        executorService.shutdown();
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
