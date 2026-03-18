package com.shop.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Phase 4] OptimisticLockRetryExecutor 단위 테스트.
 *
 * <p>낙관적 잠금 충돌 시 재시도 로직의 정확성을 검증한다.
 * 실제 DB 없이 예외를 시뮬레이션하여 테스트한다.</p>
 */
class OptimisticLockRetryExecutorTest {

    private final OptimisticLockRetryExecutor executor = new OptimisticLockRetryExecutor();

    @Test
    @DisplayName("첫 번째 시도에서 성공하면 재시도 없이 결과를 반환한다")
    void firstAttemptSuccess_noRetry() {
        String result = executor.executeWithRetry(() -> "success");
        assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("N번 실패 후 성공하면 성공 결과를 반환한다")
    void failsThenSucceeds_returnsResult() {
        AtomicInteger attempts = new AtomicInteger(0);

        // 처음 2번 실패, 3번째 성공
        String result = executor.executeWithRetry(3, () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new ObjectOptimisticLockingFailureException("Product", null);
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("최대 재시도 횟수 초과 시 예외를 전파한다")
    void exceedsMaxRetries_throwsException() {
        AtomicInteger attempts = new AtomicInteger(0);

        // 항상 실패 (maxRetries=2이므로 초기 1회 + 재시도 2회 = 총 3회 시도)
        assertThatThrownBy(() -> executor.executeWithRetry(2, () -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Coupon", null);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 초기 1회 + 재시도 2회 = 3회
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도 0회면 첫 실패 즉시 예외를 전파한다")
    void zeroRetries_throwsImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> executor.executeWithRetry(0, () -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Product", null);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("낙관적 잠금 외 다른 예외는 재시도 없이 즉시 전파한다")
    void nonOptimisticException_propagatesImmediately() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> executor.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("other error");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }
}
