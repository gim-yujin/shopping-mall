package com.shop.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProbabilisticEarlyRecomputation 분기 커버리지 보강 테스트.
 *
 * <p>기존 CacheStampedeConcurrencyTest(통합 테스트)에서 다루지 않은 분기를 검증한다:
 * - expireAfterCreate: TTL 반환
 * - expireAfterUpdate: TTL 리셋 반환
 * - expireAfterRead: 남은 시간 > TTL/2일 때 PER 건너뜀
 * - expireAfterRead: 남은 시간 <= TTL/2일 때 PER 계산 수행
 * - getEarlyRefreshCount: 카운터 누적 조회</p>
 */
class ProbabilisticEarlyRecomputationBranchTest {

    // ── expireAfterCreate / expireAfterUpdate ──

    @Test
    @DisplayName("expireAfterCreate — TTL 나노초 반환")
    void expireAfterCreate_returnsTtlNanos() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(5, TimeUnit.SECONDS, 1.0);

        long result = per.expireAfterCreate("key", "value", System.nanoTime());

        assertThat(result).isEqualTo(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    @DisplayName("expireAfterUpdate — TTL 리셋")
    void expireAfterUpdate_resetsTtl() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(10, TimeUnit.SECONDS, 1.0);

        long result = per.expireAfterUpdate("key", "value", System.nanoTime(),
                TimeUnit.SECONDS.toNanos(3)); // 기존 3초 남음

        // 갱신 시 TTL 리셋 → 10초
        assertThat(result).isEqualTo(TimeUnit.SECONDS.toNanos(10));
    }

    // ── expireAfterRead: TTL 50% 초과 → PER 건너뜀 ──

    @Test
    @DisplayName("expireAfterRead — 남은 시간 > TTL/2 → currentDuration 그대로 반환")
    void expireAfterRead_aboveHalfTtl_skipsPerCalculation() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(10, TimeUnit.SECONDS, 1.0);

        long ttlNanos = TimeUnit.SECONDS.toNanos(10);
        long remaining = ttlNanos / 2 + 1; // 50% 초과

        long result = per.expireAfterRead("key", "value", System.nanoTime(), remaining);

        // PER 계산 건너뛰고 그대로 반환
        assertThat(result).isEqualTo(remaining);
    }

    // ── expireAfterRead: TTL 50% 이하 → PER 계산 ──

    @Test
    @DisplayName("expireAfterRead — 남은 시간 <= TTL/2 → PER 계산 수행 (결과는 0 또는 currentDuration)")
    void expireAfterRead_belowHalfTtl_executesPerCalculation() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(10, TimeUnit.SECONDS, 1.0);

        long ttlNanos = TimeUnit.SECONDS.toNanos(10);
        long remaining = ttlNanos / 2; // 정확히 50%

        long result = per.expireAfterRead("key", "value", System.nanoTime(), remaining);

        // PER 계산 결과: 0(조기 만료) 또는 remaining(유지) 중 하나
        assertThat(result).isGreaterThanOrEqualTo(0);
        assertThat(result).isLessThanOrEqualTo(remaining);
    }

    // ── expireAfterRead: 매우 짧은 남은 시간 → 높은 확률로 조기 만료 ──

    @Test
    @DisplayName("expireAfterRead — 남은 시간이 거의 0이면 높은 확률로 조기 만료")
    void expireAfterRead_nearZeroRemaining_likelyEarlyExpiry() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(10, TimeUnit.SECONDS, 10.0); // beta=10으로 높임

        int earlyExpiryCount = 0;
        // 100번 반복하여 통계적 검증
        for (int i = 0; i < 100; i++) {
            long result = per.expireAfterRead("key", "value", System.nanoTime(), 1L);
            if (result == 0) {
                earlyExpiryCount++;
            }
        }

        // beta=10, remaining=1ns → 거의 대부분 조기 만료
        assertThat(earlyExpiryCount).isGreaterThan(50);
    }

    // ── getEarlyRefreshCount ──

    @Test
    @DisplayName("getEarlyRefreshCount — 조기 만료 횟수 누적")
    void getEarlyRefreshCount_accumulatesCount() {
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(10, TimeUnit.SECONDS, 100.0);

        // 매우 높은 beta로 조기 만료를 강제
        for (int i = 0; i < 50; i++) {
            per.expireAfterRead("key", "value", System.nanoTime(), 1L);
        }

        // 카운터가 누적되었는지 확인
        assertThat(per.getEarlyRefreshCount()).isGreaterThan(0);
    }
}
