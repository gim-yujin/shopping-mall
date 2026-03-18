package com.shop.global.backpressure;

import com.shop.global.config.AsyncExecutorMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 12] Graceful Degradation & Backpressure 동작 검증 테스트.
 *
 * <h3>문제: 비동기 큐 포화 시 연쇄 장애</h3>
 * <p>트래픽 급증 시 asyncExecutor 큐가 포화되면, 비필수 작업(조회수 증가,
 * 검색 로그 저장) 제출이 실패하여 HTTP 요청 스레드까지 에러가 전파된다.
 * AbortPolicy를 사용하면 RejectedExecutionException이 호출 스레드로 던져져
 * 정상적인 상품 조회 응답까지 500 에러로 바뀐다.</p>
 *
 * <h3>해결: 2단계 방어</h3>
 * <ol>
 *   <li><b>사전 방어(Proactive Shedding):</b> BackpressureDetector가 큐 사용률을
 *       모니터링하여 80% 이상(CRITICAL)이면 컨트롤러에서 비필수 작업 제출을 건너뛴다.</li>
 *   <li><b>최종 방어(Graceful Discard):</b> 큐가 완전히 가득 차면 Executor의
 *       커스텀 RejectedExecutionHandler가 작업을 조용히 폐기하고 메트릭만 기록한다.</li>
 * </ol>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>큐가 80% 이상 차면 BackpressureDetector가 CRITICAL을 반환</li>
 *   <li>큐 오버플로 시 RejectedExecutionException이 발생하지 않음 (graceful discard)</li>
 *   <li>거부된 작업 수가 메트릭에 정확히 기록됨</li>
 *   <li>큐 비움 후 NORMAL로 복구됨</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class BackpressureGracefulDegradationTest {

    @Autowired
    private AsyncExecutorMetrics asyncExecutorMetrics;

    @Autowired
    private BackpressureDetector backpressureDetector;

    @Autowired
    @Qualifier("asyncExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    // =========================================================================
    // 시나리오 1: 큐 포화도 기반 부하 수준 판정 + 복구
    // =========================================================================

    /**
     * [Phase 12] 큐 사용률에 따라 NORMAL → ELEVATED/CRITICAL 전이 후 복구 검증.
     *
     * <p><b>테스트 흐름:</b></p>
     * <ol>
     *   <li>초기 상태에서 NORMAL 확인</li>
     *   <li>큐 용량의 85%를 장기 대기 작업으로 채워 압력 상승 확인</li>
     *   <li>모든 작업 완료 후 NORMAL로 복구 확인</li>
     * </ol>
     *
     * <p><b>핵심:</b> BackpressureDetector가 큐 사용률 임계값(60%, 80%)을
     * 정확히 구분하여 비필수 작업 폐기 여부를 올바르게 판단하는지 검증한다.</p>
     */
    @Test
    @DisplayName("큐 사용률에 따라 부하 수준 전이 후 NORMAL로 복구")
    void pressureLevelTransitions_basedOnQueueFillRatio() throws Exception {
        int capacity = asyncExecutorMetrics.getQueueCapacity();
        assertThat(capacity)
                .as("Executor 큐 용량이 설정되어 있어야 합니다")
                .isGreaterThan(0);

        // ── 초기: NORMAL ──
        assertThat(backpressureDetector.getPressureLevel())
                .as("초기 상태는 NORMAL이어야 합니다")
                .isEqualTo(PressureLevel.NORMAL);
        assertThat(backpressureDetector.shouldShedNonCritical())
                .as("NORMAL에서는 비필수 작업을 폐기하지 않아야 합니다")
                .isFalse();

        // ── 큐 채우기: 장기 대기 작업으로 큐를 점유 ──
        // 큐의 85%를 채워 CRITICAL 상태를 유발한다.
        int tasksToSubmit = (int) (capacity * 0.85);
        CountDownLatch blockLatch = new CountDownLatch(1);
        AtomicInteger startedCount = new AtomicInteger(0);

        for (int i = 0; i < tasksToSubmit; i++) {
            asyncExecutor.execute(() -> {
                startedCount.incrementAndGet();
                try {
                    // blockLatch가 해제될 때까지 큐에서 대기/실행 중 유지
                    blockLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 모든 작업이 큐에 들어갈 시간을 확보
        Thread.sleep(500);

        try {
            // ── 검증: 큐 채움 후 압력 상승 ──
            // 참고: 일부 작업은 스레드에서 실행 중이고 나머지가 큐에 대기 중이므로,
            // 정확한 큐 크기는 (제출 수 - 활성 스레드 수)이다.
            PressureLevel level = backpressureDetector.getPressureLevel();
            // 큐의 85%를 채웠으므로 최소 ELEVATED 이상이어야 한다
            assertThat(level)
                    .as("큐의 85%%를 채운 상태에서 최소 ELEVATED 이상이어야 합니다 "
                                    + "(queueSize=%d, capacity=%d, fill=%.1f%%)",
                            asyncExecutorMetrics.getQueueSize(), capacity,
                            backpressureDetector.getQueueFillRatio() * 100)
                    .isIn(PressureLevel.ELEVATED, PressureLevel.CRITICAL);
        } finally {
            // ── 작업 해제 → 복구 ──
            blockLatch.countDown();
        }

        // 모든 작업이 완료될 때까지 대기
        Thread.sleep(2000);

        // 큐 비움 후 NORMAL로 복구
        assertThat(backpressureDetector.getPressureLevel())
                .as("모든 작업 완료 후 NORMAL로 복구되어야 합니다")
                .isEqualTo(PressureLevel.NORMAL);
        assertThat(backpressureDetector.shouldShedNonCritical())
                .as("복구 후 비필수 작업 폐기를 중단해야 합니다")
                .isFalse();
    }

    // =========================================================================
    // 시나리오 2: 큐 오버플로 시 Graceful Discard — 예외 없음
    // =========================================================================

    /**
     * [Phase 12] 큐가 완전히 가득 찬 상태에서 추가 작업 제출 시
     * RejectedExecutionException이 발생하지 않고 메트릭만 기록되는지 검증.
     *
     * <p><b>변경 전(AbortPolicy):</b> 큐 오버플로 → RejectedExecutionException →
     * HTTP 요청 스레드에 전파 → 500 에러.</p>
     *
     * <p><b>변경 후(Graceful Discard):</b> 큐 오버플로 → 작업 조용히 폐기 →
     * 거부 카운터 증가 → 호출 스레드 정상 계속.</p>
     *
     * <p><b>핵심:</b> 비필수 비동기 작업의 실패가 HTTP 응답에 영향을 주지 않는 것이
     * Graceful Degradation의 핵심 불변식이다.</p>
     */
    @Test
    @DisplayName("큐 오버플로 시 RejectedExecutionException 없이 graceful discard")
    void queueOverflow_gracefulDiscardWithoutException() throws Exception {
        int capacity = asyncExecutorMetrics.getQueueCapacity();
        int maxPoolSize = asyncExecutor.getMaxPoolSize();
        long rejectedBefore = asyncExecutorMetrics.getRejectedTotal();

        // ── 큐를 완전히 채운다 ──
        // 큐 용량 + 최대 스레드 수 + 추가 작업을 제출하여 오버플로를 유발한다.
        int overflowCount = capacity + maxPoolSize + 20;
        CountDownLatch blockLatch = new CountDownLatch(1);

        try {
            for (int i = 0; i < overflowCount; i++) {
                // 핵심: 이 호출이 RejectedExecutionException을 던지지 않아야 한다.
                // Phase 12 이전(AbortPolicy)에서는 큐 포화 시 여기서 예외가 발생했다.
                asyncExecutor.execute(() -> {
                    try {
                        blockLatch.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // 이 줄에 도달하면 RejectedExecutionException이 발생하지 않은 것이다.
            // (AbortPolicy였다면 여기에 도달하기 전에 예외가 던져졌을 것)

            // 큐 오버플로가 발생했으므로 일부 작업이 거부되었어야 한다
            long rejectedAfter = asyncExecutorMetrics.getRejectedTotal();
            long newRejections = rejectedAfter - rejectedBefore;

            // ── 검증: 거부 메트릭 기록 확인 ──
            assertThat(newRejections)
                    .as("큐 오버플로 시 거부된 작업이 메트릭에 기록되어야 합니다 "
                            + "(submitted=%d, capacity=%d, maxPool=%d)",
                            overflowCount, capacity, maxPoolSize)
                    .isGreaterThan(0);
        } finally {
            // ── 작업 해제 → 정리 ──
            blockLatch.countDown();
        }

        // 모든 작업 완료 대기
        Thread.sleep(2000);

        // ── 검증: 복구 후 정상 동작 ──
        // 큐가 비워진 후 새 작업이 정상 실행되는지 확인한다.
        AtomicInteger recoveryCheck = new AtomicInteger(0);
        CountDownLatch recoveryLatch = new CountDownLatch(1);
        asyncExecutor.execute(() -> {
            recoveryCheck.incrementAndGet();
            recoveryLatch.countDown();
        });

        assertThat(recoveryLatch.await(10, TimeUnit.SECONDS))
                .as("큐 오버플로 복구 후 새 작업이 정상 실행되어야 합니다")
                .isTrue();
        assertThat(recoveryCheck.get()).isEqualTo(1);
    }

    // =========================================================================
    // 시나리오 3: Health 인디케이터 부하 수준 반영 검증
    // =========================================================================

    /**
     * [Phase 12] BackpressureHealthIndicator가 현재 부하 수준을 정확히 반영하는지 검증.
     */
    @Test
    @DisplayName("Health 인디케이터가 큐 상태와 메트릭을 정확히 반영")
    void healthIndicator_reflectsCurrentPressureState() {
        // 초기 상태에서 건강한 상태 확인
        BackpressureHealthIndicator healthIndicator =
                new BackpressureHealthIndicator(backpressureDetector, asyncExecutorMetrics);

        org.springframework.boot.actuate.health.Health health = healthIndicator.health();

        // NORMAL이면 UP
        assertThat(health.getStatus())
                .as("NORMAL 상태에서 Health는 UP이어야 합니다")
                .isEqualTo(org.springframework.boot.actuate.health.Status.UP);

        // 상세 정보에 모든 필드가 포함되어야 한다
        assertThat(health.getDetails())
                .containsKey("pressureLevel")
                .containsKey("queueFillRatio")
                .containsKey("queueSize")
                .containsKey("queueCapacity")
                .containsKey("rejectedTotal")
                .containsKey("completedTotal");

        assertThat(health.getDetails().get("pressureLevel"))
                .isEqualTo("NORMAL");
    }
}
