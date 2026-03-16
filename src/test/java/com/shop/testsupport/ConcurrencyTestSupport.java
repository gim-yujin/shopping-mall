package com.shop.testsupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public final class ConcurrencyTestSupport {

    private ConcurrencyTestSupport() {
    }

    public static void assertAllWorkersCompleted(
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            long readyTimeout,
            long doneTimeout,
            TimeUnit unit,
            int threadCount,
            IntSupplier successCountSupplier,
            IntSupplier failCountSupplier
    ) throws InterruptedException {
        assertThat(ready.await(readyTimeout, unit))
                .as("모든 작업 스레드가 준비되어야 합니다 (threadCount=%d, readyRemaining=%d)",
                        threadCount, ready.getCount())
                .isTrue();

        start.countDown();

        assertThat(done.await(doneTimeout, unit))
                .as("지정 시간 내 모든 작업이 완료되어야 합니다 (threadCount=%d, doneRemaining=%d, success=%d, fail=%d)",
                        threadCount, done.getCount(), successCountSupplier.getAsInt(), failCountSupplier.getAsInt())
                .isTrue();
    }
}
