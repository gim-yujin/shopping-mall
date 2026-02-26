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

        log.info("asyncExecutor metrics: queue.size={}, rejected.total={}, completed.total={}",
                asyncExecutorMetrics.getQueueSize(),
                asyncExecutorMetrics.getRejectedTotal(),
                asyncExecutorMetrics.getCompletedTotal());
    }
}
