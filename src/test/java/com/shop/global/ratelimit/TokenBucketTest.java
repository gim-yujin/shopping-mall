package com.shop.global.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenBucket 알고리즘 단위 테스트.
 *
 * <p>토큰 소비, 버킷 소진, 리필, CAS 동시성 안전성을 검증한다.</p>
 */
class TokenBucketTest {

    @Test
    @DisplayName("버킷 생성 시 용량만큼 토큰이 채워진다")
    void initialTokensEqualCapacity() {
        TokenBucket bucket = new TokenBucket(10, 10, 60_000);

        assertThat(bucket.getAvailableTokens()).isEqualTo(10);
        assertThat(bucket.getCapacity()).isEqualTo(10);
    }

    @Test
    @DisplayName("토큰이 있으면 소비에 성공하고 잔여 토큰이 감소한다")
    void consumeSucceedsWhenTokensAvailable() {
        TokenBucket bucket = new TokenBucket(5, 5, 60_000);

        TokenBucket.ConsumeResult result = bucket.tryConsume();

        // 5개 → 1개 소비 → 4개 남음
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(4);
        assertThat(result.retryAfterSec()).isZero();
    }

    @Test
    @DisplayName("토큰이 소진되면 소비에 실패하고 retryAfter를 반환한다")
    void consumeFailsWhenBucketEmpty() {
        TokenBucket bucket = new TokenBucket(3, 3, 60_000);

        // 3개 토큰 모두 소비
        bucket.tryConsume();
        bucket.tryConsume();
        bucket.tryConsume();

        // 4번째 요청은 거부
        TokenBucket.ConsumeResult result = bucket.tryConsume();
        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isZero();
        assertThat(result.retryAfterSec()).isGreaterThan(0);
    }

    @Test
    @DisplayName("연속 소비로 토큰이 정확히 감소한다")
    void tokensDecreaseOnEachConsume() {
        TokenBucket bucket = new TokenBucket(5, 5, 60_000);

        for (int i = 4; i >= 0; i--) {
            TokenBucket.ConsumeResult result = bucket.tryConsume();
            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(i);
        }

        // 5번째에서 정확히 소진
        assertThat(bucket.tryConsume().allowed()).isFalse();
    }

    @Test
    @DisplayName("여러 스레드에서 동시 소비해도 용량을 초과하지 않는다")
    void concurrentConsumeDoesNotExceedCapacity() throws InterruptedException {
        // 용량 100인 버킷에 200개 스레드가 동시에 소비 시도
        int capacity = 100;
        TokenBucket bucket = new TokenBucket(capacity, capacity, 60_000);

        int threadCount = 200;
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    if (bucket.tryConsume().allowed()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // CAS 기반 lock-free이므로 정확히 capacity(100)개만 성공해야 한다
        assertThat(successCount.get()).isEqualTo(capacity);
    }

    @Test
    @DisplayName("ConsumeResult 레코드의 필드가 올바르게 설정된다")
    void consumeResultFieldsCorrect() {
        // 성공 결과
        TokenBucket.ConsumeResult success = new TokenBucket.ConsumeResult(true, 4, 0);
        assertThat(success.allowed()).isTrue();
        assertThat(success.remainingTokens()).isEqualTo(4);
        assertThat(success.retryAfterSec()).isZero();

        // 실패 결과
        TokenBucket.ConsumeResult failure = new TokenBucket.ConsumeResult(false, 0, 30);
        assertThat(failure.allowed()).isFalse();
        assertThat(failure.remainingTokens()).isZero();
        assertThat(failure.retryAfterSec()).isEqualTo(30);
    }
}
