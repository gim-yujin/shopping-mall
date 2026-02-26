package com.shop.global.config;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

public class AsyncExecutorMetrics {

    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong completedTotal = new AtomicLong();
    private volatile IntSupplier queueSizeSupplier = () -> 0;

    public void bindQueueSizeSupplier(IntSupplier queueSizeSupplier) {
        this.queueSizeSupplier = queueSizeSupplier;
    }

    public void incrementRejected() {
        rejectedTotal.incrementAndGet();
    }

    public void incrementCompleted() {
        completedTotal.incrementAndGet();
    }

    public long getRejectedTotal() {
        return rejectedTotal.get();
    }

    public long getCompletedTotal() {
        return completedTotal.get();
    }

    public int getQueueSize() {
        return queueSizeSupplier.getAsInt();
    }
}
