package com.shop.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OptimisticLockRetryExecutor 분기 커버리지 보강 테스트.
 *
 * <p>기존 테스트에서 다루지 않은 분기를 검증한다:
 * - InterruptedException 발생 시 스레드 인터럽트 플래그 복원 후 원래 예외 전파
 * - 기본 재시도 횟수(DEFAULT_MAX_RETRIES=3) 사용 시 정확한 시도 횟수</p>
 */
class OptimisticLockRetryExecutorBranchTest {

    private final OptimisticLockRetryExecutor executor = new OptimisticLockRetryExecutor();

    @Test
    @DisplayName("InterruptedException 발생 시 스레드 인터럽트 플래그가 복원되고 원래 예외가 전파된다")
    void interrupted_restoresFlagAndThrowsOriginal() {
        AtomicInteger attempts = new AtomicInteger(0);

        // 다른 스레드에서 실행하여 인터럽트 테스트
        Thread testThread = new Thread(() -> {
            try {
                executor.executeWithRetry(3, () -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        // 첫 번째 시도: 낙관적 잠금 충돌 후 Thread.sleep 중 인터럽트 발생 유도
                        Thread.currentThread().interrupt();
                        throw new ObjectOptimisticLockingFailureException("Product", null);
                    }
                    return "unreachable";
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                // InterruptedException 발생 시 원래의 OptimisticLockingFailureException 전파
                // 스레드 인터럽트 플래그가 복원되었는지 확인
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }
        });

        testThread.start();
        try {
            testThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 첫 시도에서 인터럽트되어 재시도 없이 종료
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("기본 재시도 횟수(3회) 사용 — executeWithRetry(Supplier) 오버로드")
    void defaultRetries_usesThreeRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        // 기본 maxRetries=3이므로 초기 1회 + 재시도 3회 = 총 4회 시도 후 실패
        assertThatThrownBy(() -> executor.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Product", null);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // DEFAULT_MAX_RETRIES=3 → 초기 1회 + 재시도 3회 = 4회
        assertThat(attempts.get()).isEqualTo(4);
    }
}
