package com.shop.global.backpressure;

import com.shop.global.config.AsyncExecutorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [Phase 12] 비동기 Executor 큐 포화도를 기반으로 시스템 부하 수준을 판정한다.
 *
 * <h3>왜 큐 포화도인가?</h3>
 * <p>CPU 사용률이나 GC 압력은 JMX를 통해 확인할 수 있지만, 이커머스 애플리케이션에서
 * 가장 먼저 포화되는 리소스는 스레드 풀 큐다.
 * 트래픽 급증 → 비동기 작업 제출 증가 → 큐 포화 → RejectedExecutionException
 * 순서로 장애가 전파되므로, 큐 사용률이 가장 빠른 선행 지표(leading indicator)다.</p>
 *
 * <h3>임계값 설계 근거</h3>
 * <ul>
 *   <li>60% (ELEVATED): 트래픽 증가 추세 감지. Netflix Hystrix의 서킷 브레이커도
 *       유사한 비율(50~70%)에서 경고를 시작한다.</li>
 *   <li>80% (CRITICAL): 큐 오버플로까지 여유분 20%만 남은 상태.
 *       비필수 작업을 즉시 폐기하여 남은 여유분을 핵심 작업에 확보한다.</li>
 * </ul>
 *
 * <h3>스레드 안전성</h3>
 * <p>{@link AsyncExecutorMetrics}의 큐 크기 조회는 {@code LinkedBlockingQueue.size()}로
 * 원자적 읽기이며, 부하 판정은 읽기 전용이므로 동기화가 불필요하다.</p>
 */
@Component
public class BackpressureDetector {

    private static final Logger log = LoggerFactory.getLogger(BackpressureDetector.class);

    /**
     * 큐 사용률 60% 이상이면 ELEVATED로 전환.
     */
    private static final double ELEVATED_THRESHOLD = 0.6;

    /**
     * 큐 사용률 80% 이상이면 CRITICAL로 전환 — 비필수 작업 즉시 폐기.
     */
    private static final double CRITICAL_THRESHOLD = 0.8;

    private final AsyncExecutorMetrics metrics;

    public BackpressureDetector(AsyncExecutorMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 현재 시스템 부하 수준을 판정한다.
     *
     * <p>큐 용량이 0이면(Executor 미초기화) 항상 NORMAL을 반환한다.
     * 이는 테스트 환경에서 Executor가 아직 바인딩되지 않은 경우를 안전하게 처리한다.</p>
     *
     * @return 현재 부하 수준
     */
    public PressureLevel getPressureLevel() {
        int capacity = metrics.getQueueCapacity();
        if (capacity <= 0) {
            return PressureLevel.NORMAL;
        }

        double fillRatio = (double) metrics.getQueueSize() / capacity;

        if (fillRatio >= CRITICAL_THRESHOLD) {
            return PressureLevel.CRITICAL;
        }
        if (fillRatio >= ELEVATED_THRESHOLD) {
            return PressureLevel.ELEVATED;
        }
        return PressureLevel.NORMAL;
    }

    /**
     * 비필수 비동기 작업을 폐기해야 하는지 판단한다.
     *
     * <p>컨트롤러에서 조회수 증가, 검색 로그 저장 등 비필수 작업을 호출하기 전에
     * 이 메서드를 확인하여, CRITICAL 상태에서는 작업 제출 자체를 건너뛴다.
     * 이렇게 하면 큐에 작업이 쌓이는 것을 사전에 방지한다.</p>
     *
     * @return true이면 비필수 작업을 건너뛰어야 한다
     */
    public boolean shouldShedNonCritical() {
        return getPressureLevel() == PressureLevel.CRITICAL;
    }

    /**
     * 현재 큐 사용률(0.0 ~ 1.0)을 반환한다.
     * Health 인디케이터와 메트릭 로깅에서 사용한다.
     */
    public double getQueueFillRatio() {
        int capacity = metrics.getQueueCapacity();
        if (capacity <= 0) {
            return 0.0;
        }
        return (double) metrics.getQueueSize() / capacity;
    }
}
