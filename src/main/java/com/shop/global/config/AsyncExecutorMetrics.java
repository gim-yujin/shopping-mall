package com.shop.global.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicLong;

public class AsyncExecutorMetrics {

    private final AtomicLong rejectedTotal = new AtomicLong();
    private volatile ThreadPoolTaskExecutor executor;

    // [Phase 12] Executor 설정 시점의 큐 용량을 캐싱한다.
    // LinkedBlockingQueue.remainingCapacity()는 동적이 아니라 생성 시 고정되므로
    // 한 번만 읽어 volatile 필드에 저장해도 안전하다.
    private volatile int queueCapacity;

    public void bindExecutor(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
        // [Phase 12] 큐 용량 = 현재 큐 크기 + 남은 용량 (큐 생성 시 고정값)
        this.queueCapacity = executor.getThreadPoolExecutor().getQueue().size()
                + executor.getThreadPoolExecutor().getQueue().remainingCapacity();
    }

    public void incrementRejected() {
        rejectedTotal.incrementAndGet();
    }

    public long getRejectedTotal() {
        return rejectedTotal.get();
    }

    public long getCompletedTotal() {
        if (executor == null) {
            return 0L;
        }
        return executor.getThreadPoolExecutor().getCompletedTaskCount();
    }

    public int getQueueSize() {
        if (executor == null) {
            return 0;
        }
        return executor.getThreadPoolExecutor().getQueue().size();
    }

    /**
     * [Phase 12] 큐의 전체 용량을 반환한다.
     * {@link com.shop.global.backpressure.BackpressureDetector}가 큐 사용률(fillRatio)을
     * 계산할 때 분모로 사용한다.
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * [Phase 12] 현재 작업을 실행 중인 활성 스레드 수를 반환한다.
     * Health 인디케이터와 메트릭 로깅에서 사용한다.
     */
    public int getActiveCount() {
        if (executor == null) {
            return 0;
        }
        return executor.getThreadPoolExecutor().getActiveCount();
    }
}
