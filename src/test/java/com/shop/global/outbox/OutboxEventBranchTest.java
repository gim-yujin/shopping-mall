package com.shop.global.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxEvent 엔티티 분기 커버리지 보강 테스트.
 *
 * <p>기존 OutboxEventTest에서 다루지 않은 분기를 검증한다:
 * - deprecated 메서드(incrementRetry, markFailed): Phase 15 이전 호환용
 * - truncateError(null): null 에러 메시지 처리
 * - getCreatedAt getter</p>
 */
class OutboxEventBranchTest {

    @Test
    @DisplayName("incrementRetry — deprecated 메서드가 retryCount를 1 증가시킨다")
    @SuppressWarnings("deprecation")
    void incrementRetry_incrementsRetryCount() {
        OutboxEvent event = new OutboxEvent("TEST", "{}");
        assertThat(event.getRetryCount()).isEqualTo(0);

        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(1);

        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("markFailed — deprecated 메서드가 FAILED 상태로 전이하고 processedAt을 설정한다")
    @SuppressWarnings("deprecation")
    void markFailed_setsFailedStatusAndProcessedAt() {
        OutboxEvent event = new OutboxEvent("TEST", "{}");
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(event.getProcessedAt()).isNull();

        event.markFailed();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("scheduleRetry(null 에러) — truncateError가 null을 그대로 반환한다")
    void scheduleRetry_withNullError_setsNullLastError() {
        OutboxEvent event = new OutboxEvent("TEST", "{}");

        // null 에러 메시지로 scheduleRetry 호출 → truncateError(null) → null 반환
        event.scheduleRetry(null, 10);

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("getCreatedAt — 생성 시점이 올바르게 반환된다")
    void getCreatedAt_returnsNonNull() {
        OutboxEvent event = new OutboxEvent("TEST", "{}");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("moveToDeadLetter(null 에러) — null 에러 메시지도 정상 처리된다")
    void moveToDeadLetter_withNullError_handlesGracefully() {
        OutboxEvent event = new OutboxEvent("TEST", "{}");

        event.moveToDeadLetter(null);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_DEAD_LETTER);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getNextRetryAt()).isNull();
    }
}
