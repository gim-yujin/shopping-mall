package com.shop.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 16] ProbabilisticEarlyRecomputation(PER) 단위 테스트.
 *
 * <p>XFetch 알고리즘의 핵심 동작을 검증한다:
 * 생성/갱신 시 표준 TTL 반환, 읽기 시 확률적 조기 만료,
 * TTL 50% 이상 잔여 시 최적화 스킵.</p>
 */
class ProbabilisticEarlyRecomputationTest {

    private static final long TTL_MINUTES = 1;
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(TTL_MINUTES);
    private static final double BETA = 1.0;

    private final ProbabilisticEarlyRecomputation per =
            new ProbabilisticEarlyRecomputation(TTL_MINUTES, TimeUnit.MINUTES, BETA);

    @Nested
    @DisplayName("엔트리 생성 — expireAfterCreate")
    class ExpireAfterCreate {

        @Test
        @DisplayName("최초 생성 시 표준 TTL(나노초)을 반환한다")
        void returnsStandardTtl() {
            long result = per.expireAfterCreate("key", "value", System.nanoTime());
            assertThat(result).isEqualTo(TTL_NANOS);
        }
    }

    @Nested
    @DisplayName("엔트리 갱신 — expireAfterUpdate")
    class ExpireAfterUpdate {

        @Test
        @DisplayName("갱신 시 TTL을 리셋한다")
        void resetsTtlOnUpdate() {
            long result = per.expireAfterUpdate("key", "value", System.nanoTime(), TTL_NANOS / 2);
            assertThat(result).isEqualTo(TTL_NANOS);
        }
    }

    @Nested
    @DisplayName("엔트리 읽기 — expireAfterRead (PER 핵심 로직)")
    class ExpireAfterRead {

        @Test
        @DisplayName("남은 TTL이 50% 이상이면 PER 계산을 건너뛰고 현재 만료 시간을 유지한다")
        void skipsPerWhenMoreThanHalfTtlRemaining() {
            // TTL의 60%가 남은 상태 → PER 스킵
            long remainingNanos = (long) (TTL_NANOS * 0.6);
            long result = per.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);
            assertThat(result).isEqualTo(remainingNanos);
        }

        @Test
        @DisplayName("남은 TTL이 50% 미만이면 PER 계산이 실행된다 (0 또는 currentDuration 반환)")
        void executesPerWhenLessThanHalfTtlRemaining() {
            // TTL의 10%만 남은 상태 → PER 계산 실행
            long remainingNanos = TTL_NANOS / 10;
            long result = per.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);

            // PER은 확률적이므로 0(조기 만료) 또는 remainingNanos(유지) 중 하나를 반환
            assertThat(result).isIn(0L, remainingNanos);
        }

        @Test
        @DisplayName("남은 TTL이 0에 가까우면 높은 확률로 조기 만료(0)를 반환한다")
        void highProbabilityOfEarlyExpiryWhenAlmostExpired() {
            // TTL의 0.1%만 남은 극단적 상태 — 거의 항상 조기 만료
            long remainingNanos = TTL_NANOS / 1000;
            int earlyExpiryCount = 0;
            int iterations = 100;

            for (int i = 0; i < iterations; i++) {
                long result = per.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);
                if (result == 0) {
                    earlyExpiryCount++;
                }
            }

            // 0.1% 잔여 시 거의 100% 조기 만료 예상 (최소 90% 이상)
            assertThat(earlyExpiryCount).isGreaterThan(90);
        }

        @Test
        @DisplayName("TTL의 50% 바로 위에서는 PER이 실행되지 않는다 (경계값)")
        void boundaryAtExactlyHalfTtl() {
            // 정확히 TTL/2 + 1 나노초 → 스킵
            long remainingNanos = TTL_NANOS / 2 + 1;
            long result = per.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);
            assertThat(result).isEqualTo(remainingNanos);
        }
    }

    @Nested
    @DisplayName("조기 만료 카운터 — getEarlyRefreshCount")
    class EarlyRefreshCount {

        @Test
        @DisplayName("조기 만료가 발생하면 카운터가 증가한다")
        void incrementsOnEarlyExpiry() {
            ProbabilisticEarlyRecomputation testPer =
                    new ProbabilisticEarlyRecomputation(TTL_MINUTES, TimeUnit.MINUTES, BETA);

            // 매우 적은 잔여 TTL로 조기 만료를 반복 유도
            long remainingNanos = 1; // 1 나노초 → 거의 확실히 조기 만료
            for (int i = 0; i < 100; i++) {
                testPer.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);
            }

            assertThat(testPer.getEarlyRefreshCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("조기 만료가 발생하지 않으면 카운터는 0이다")
        void zeroWhenNoEarlyExpiry() {
            ProbabilisticEarlyRecomputation testPer =
                    new ProbabilisticEarlyRecomputation(TTL_MINUTES, TimeUnit.MINUTES, BETA);

            // TTL의 80% 잔여 → PER 스킵 → 카운터 미증가
            long remainingNanos = (long) (TTL_NANOS * 0.8);
            for (int i = 0; i < 100; i++) {
                testPer.expireAfterRead("key", "value", System.nanoTime(), remainingNanos);
            }

            assertThat(testPer.getEarlyRefreshCount()).isZero();
        }
    }

    @Nested
    @DisplayName("beta 계수 영향")
    class BetaEffect {

        @Test
        @DisplayName("beta가 클수록 조기 만료 확률이 높아진다")
        void higherBetaIncreasesEarlyExpiry() {
            ProbabilisticEarlyRecomputation lowBeta =
                    new ProbabilisticEarlyRecomputation(TTL_MINUTES, TimeUnit.MINUTES, 0.5);
            ProbabilisticEarlyRecomputation highBeta =
                    new ProbabilisticEarlyRecomputation(TTL_MINUTES, TimeUnit.MINUTES, 2.0);

            // TTL의 30%가 남은 중간 구간에서 비교
            long remainingNanos = (long) (TTL_NANOS * 0.3);
            int lowBetaEarlyCount = 0;
            int highBetaEarlyCount = 0;
            int iterations = 1000;

            for (int i = 0; i < iterations; i++) {
                if (lowBeta.expireAfterRead("k", "v", System.nanoTime(), remainingNanos) == 0) {
                    lowBetaEarlyCount++;
                }
                if (highBeta.expireAfterRead("k", "v", System.nanoTime(), remainingNanos) == 0) {
                    highBetaEarlyCount++;
                }
            }

            // 높은 beta가 더 많은 조기 만료를 유도해야 한다
            assertThat(highBetaEarlyCount).isGreaterThan(lowBetaEarlyCount);
        }
    }
}
