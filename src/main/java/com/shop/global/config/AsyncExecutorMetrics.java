package com.shop.global.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicLong;

public class AsyncExecutorMetrics {

    private final AtomicLong rejectedTotal = new AtomicLong();
    private volatile ThreadPoolTaskExecutor executor;

    public void bindExecutor(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
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
}
