package com.shop.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AsyncExecutorMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(AsyncExecutorMetricsLogger.class);

    private final AsyncExecutorMetrics asyncExecutorMetrics;
    private final boolean enabled;

    public AsyncExecutorMetricsLogger(
            AsyncExecutorMetrics asyncExecutorMetrics,
            @Value("${app.async.metrics.logging-enabled:true}") boolean enabled
    ) {
        this.asyncExecutorMetrics = asyncExecutorMetrics;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.async.metrics.log-interval-ms:30000}")
    public void logMetrics() {
        if (!enabled) {
            return;
        }

        // [Phase 12] 큐 용량 대비 사용률과 활성 스레드 수를 추가 로깅하여
        // 부하 추세를 모니터링할 수 있도록 한다.
        int capacity = asyncExecutorMetrics.getQueueCapacity();
        int queueSize = asyncExecutorMetrics.getQueueSize();
        String fillRatio = capacity > 0
                ? String.format("%.1f%%", (double) queueSize / capacity * 100)
                : "N/A";
        log.info("asyncExecutor metrics: queue.size={}, queue.capacity={}, queue.fill={}, "
                        + "active.threads={}, rejected.total={}, completed.total={}",
                queueSize, capacity, fillRatio,
                asyncExecutorMetrics.getActiveCount(),
                asyncExecutorMetrics.getRejectedTotal(),
                asyncExecutorMetrics.getCompletedTotal());
    }
}
