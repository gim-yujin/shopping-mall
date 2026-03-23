package com.shop.global.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 15] OutboxEvent 엔티티 단위 테스트.
 *
 * <p>지수 백오프 재시도 스케줄링, Dead Letter 전이, Dead Letter 복구,
 * 에러 메시지 절삭 등 Phase 15에서 추가된 도메인 로직을 검증한다.</p>
 */
class OutboxEventTest {

    @Nested
    @DisplayName("지수 백오프 재시도 스케줄링 — scheduleRetry")
    class ScheduleRetry {

        @Test
        @DisplayName("1차 실패: retryCount=1, nextRetryAt = now + baseDelay")
        void firstRetryUsesBaseDelay() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            LocalDateTime before = LocalDateTime.now();

            event.scheduleRetry("에러 발생", 10);

            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(event.getLastError()).isEqualTo("에러 발생");
            // baseDelay=10 × 2^0 = 10초
            assertThat(event.getNextRetryAt()).isAfterOrEqualTo(before.plusSeconds(10));
        }

        @Test
        @DisplayName("3차 실패: nextRetryAt 간격이 지수적으로 증가한다 (10 → 20 → 40)")
        void backoffGrowsExponentially() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");

            // 1차: 10 × 2^0 = 10초
            event.scheduleRetry("err1", 10);
            LocalDateTime retry1 = event.getNextRetryAt();

            // 2차: 10 × 2^1 = 20초
            event.scheduleRetry("err2", 10);
            LocalDateTime retry2 = event.getNextRetryAt();

            // 3차: 10 × 2^2 = 40초
            event.scheduleRetry("err3", 10);
            LocalDateTime retry3 = event.getNextRetryAt();

            assertThat(retry2).isAfter(retry1);
            assertThat(retry3).isAfter(retry2);
            assertThat(event.getRetryCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("백오프 간격이 300초(5분)를 초과하지 않는다")
        void backoffIsCappedAt300Seconds() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            // retryCount를 높여서 2^retryCount가 매우 크게 만든다
            for (int i = 0; i < 10; i++) {
                event.scheduleRetry("err", 10);
            }

            LocalDateTime before = LocalDateTime.now();
            // 300초 + 약간의 여유를 두고 검증
            assertThat(event.getNextRetryAt()).isBefore(before.plusSeconds(310));
        }

        @Test
        @DisplayName("에러 메시지가 500자를 초과하면 절삭된다")
        void truncatesLongErrorMessage() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            String longError = "x".repeat(1000);

            event.scheduleRetry(longError, 10);

            assertThat(event.getLastError()).hasSize(500);
        }

        @Test
        @DisplayName("jitterFactor=0 → 지터 없이 고정 백오프와 동일")
        void zeroJitter_sameAsFixedBackoff() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            LocalDateTime before = LocalDateTime.now();

            event.scheduleRetry("에러", 10, 0.0);

            assertThat(event.getRetryCount()).isEqualTo(1);
            // baseDelay=10 × 2^0 = 10초, 지터 없음
            assertThat(event.getNextRetryAt()).isAfterOrEqualTo(before.plusSeconds(10));
            assertThat(event.getNextRetryAt()).isBefore(before.plusSeconds(12));
        }

        @Test
        @DisplayName("jitterFactor=0.25 → 백오프 간격이 ±25% 범위 내에서 변동한다")
        void jitterApplied_withinExpectedRange() {
            // 100회 반복하여 지터 범위를 통계적으로 검증한다.
            // baseDelay=100, retryCount=1 → 기본 100초, 지터 ±25% → 75~125초 범위
            int withinRange = 0;
            for (int i = 0; i < 100; i++) {
                OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
                LocalDateTime before = LocalDateTime.now();

                event.scheduleRetry("에러", 100, 0.25);

                long actualSec = java.time.Duration.between(before, event.getNextRetryAt()).getSeconds();
                // 허용 범위: 75초 ~ 125초 (±25%), 시간 측정 오차 ±2초
                if (actualSec >= 73 && actualSec <= 127) {
                    withinRange++;
                }
            }
            // 100회 중 최소 95회는 범위 내여야 한다
            assertThat(withinRange).isGreaterThanOrEqualTo(95);
        }

        @Test
        @DisplayName("jitter 적용 시 delaySec가 최소 1초 이상 보장된다")
        void jitter_minimumOneSecond() {
            // baseDelay=1, retryCount=1 → 기본 1초, jitter=0.99 → 최악의 경우 0.01초
            // 하지만 Math.max(1L, ...)로 최소 1초 보장
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            LocalDateTime before = LocalDateTime.now();

            event.scheduleRetry("에러", 1, 0.99);

            assertThat(event.getNextRetryAt()).isAfterOrEqualTo(before.plusSeconds(1));
        }
    }

    @Nested
    @DisplayName("Dead Letter 전이 — moveToDeadLetter")
    class MoveToDeadLetter {

        @Test
        @DisplayName("DEAD_LETTER 상태로 전이하고 processedAt과 lastError를 기록한다")
        void transitionsToDeadLetter() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED, "{}");

            event.moveToDeadLetter("외부 서비스 영구 장애");

            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_DEAD_LETTER);
            assertThat(event.isDeadLetter()).isTrue();
            assertThat(event.getProcessedAt()).isNotNull();
            assertThat(event.getLastError()).isEqualTo("외부 서비스 영구 장애");
            assertThat(event.getNextRetryAt()).isNull();
            assertThat(event.getRetryCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Dead Letter 복구 — requeueFromDeadLetter")
    class RequeueFromDeadLetter {

        @Test
        @DisplayName("PENDING으로 되돌리고 모든 실패 정보를 초기화한다")
        void resetsToPendingState() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED, "{}");
            event.moveToDeadLetter("장애");

            event.requeueFromDeadLetter();

            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(event.isPending()).isTrue();
            assertThat(event.isDeadLetter()).isFalse();
            assertThat(event.getRetryCount()).isZero();
            assertThat(event.getProcessedAt()).isNull();
            assertThat(event.getNextRetryAt()).isNull();
            assertThat(event.getLastError()).isNull();
        }
    }
}
